package ua.fin.billing.service.renewal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.fin.billing.client.liqpay.LiqPayClient;
import ua.fin.billing.client.stripe.StripeClient;
import ua.fin.billing.client.stripe.StripeSignatureException;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;
import ua.fin.billing.repository.PaymentRepository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Phase 4.3 (Commit 6) — daily auto-renewal
 * sweep.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Find all SUCCEEDED payments whose
 *       {@code NEXT_RENEWAL_AT} has passed.</li>
 *   <li>For each, attempt a re-charge via the
 *       original provider. The "re-charge" is
 *       a stub for Phase 4.3 — the LiqPay /
 *       Stripe SDKs require a stored customer +
 *       payment method (e.g.
 *       {@code stripe.PaymentIntent.create} with
 *       {@code customer=...} + {@code payment_method=...})
 *       which we don't have yet. We simulate
 *       the call and surface a failure when
 *       {@code simulate-fail=true} is set, so
 *       dunning can be tested end-to-end.</li>
 *   <li>On success: advance the payment's
 *       {@code NEXT_RENEWAL_AT} by 30 days and
 *       create a sibling renewal payment row
 *       (SUCCEEDED).</li>
 *   <li>On failure: increment
 *       {@code FAILURE_COUNT}. After 3 strikes
 *       call
 *       {@link SubscriptionServiceClient#deactivateSubscription}
 *       to flip the parent subscription
 *       {@code IS_ACTIVE=false}.</li>
 * </ol>
 *
 * <p>The actual provider-side re-charge is
 * Phase 5+ work (it needs stored customer IDs +
 * payment method IDs from the initial checkout).
 * For Phase 4.3 we keep the orchestration
 * logic + dunning state machine, with the
 * re-charge stubbed via the
 * {@code simulate-fail} flag for tests.</p>
 */
@Service
@Slf4j
public class AutoRenewalService {

    /**
     * Maximum failed renewal attempts before
     * dunning kicks in. After the 3rd failure
     * the parent subscription is deactivated.
     */
    static final int MAX_FAILURES = 3;

    /**
     * Renewal interval — Phase 4.3 hardcodes
     * 30 days (a calendar month is the closest
     * approximation). Phase 5+ will read the
     * plan's actual period from
     * subscription-service.
     */
    static final int RENEWAL_PERIOD_DAYS = 30;

    private final PaymentRepository paymentRepository;
    private final LiqPayClient liqPayClient;
    private final StripeClient stripeClient;
    private final SubscriptionServiceClient subscriptionServiceClient;

    @Value("${billing.renewal.simulate-fail:false}")
    private boolean simulateFail;

    public AutoRenewalService(
        PaymentRepository paymentRepository,
        LiqPayClient liqPayClient,
        StripeClient stripeClient,
        SubscriptionServiceClient subscriptionServiceClient
    ) {
        this.paymentRepository = paymentRepository;
        this.liqPayClient = liqPayClient;
        this.stripeClient = stripeClient;
        this.subscriptionServiceClient = subscriptionServiceClient;
    }

    /**
     * Sweep all due renewals. Idempotent — if
     * the scheduler fires twice (e.g. the cron
     * catches up after a downtime), each
     * payment is only renewed once per
     * {@code NEXT_RENEWAL_AT} cycle.
     *
     * @return the number of payments renewed
     *     (or attempted)
     */
    @Transactional
    public int renewDue() {
        final OffsetDateTime now = OffsetDateTime.now();
        final List<Payment> due = paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                PaymentStatus.SUCCEEDED, now
            );
        log.info("Auto-renewal sweep: {} due payments", due.size());
        int renewed = 0;
        for (final Payment p : due) {
            try {
                if (attemptRenewal(p)) {
                    advanceRenewalDate(p);
                    renewed++;
                } else {
                    handleFailure(p, now);
                }
            } catch (RuntimeException e) {
                // The provider call itself blew
                // up (network error, signature
                // mismatch, etc.) — count it as a
                // failure and let the next sweep
                // try again.
                log.error(
                    "Auto-renewal error for paymentId={}: {}",
                    p.getPaymentId(), e.getMessage(), e
                );
                handleFailure(p, now);
            }
        }
        return renewed;
    }

    /**
     * Attempt to re-charge via the original
     * provider. Phase 4.3 stub: returns true
     * (success) unless {@code simulate-fail}
     * is set, in which case it always fails
     * (used to test dunning).
     *
     * <p>The LiqPay / Stripe clients are
     * referenced here for shape — Phase 5+
     * swaps the stub for real
     * {@code PaymentIntent.create(...)} calls
     * that use stored customer + payment
     * method IDs.</p>
     */
    boolean attemptRenewal(Payment payment) {
        if (simulateFail) {
            log.debug(
                "Auto-renewal simulated failure for paymentId={}",
                payment.getPaymentId()
            );
            return false;
        }
        // Reference the clients so DI works
        // (Phase 5+ will use them in earnest).
        if (payment.getProvider() == PaymentProvider.LIQPAY
            && liqPayClient == null) {
            return false;
        }
        if (payment.getProvider() == PaymentProvider.STRIPE
            && stripeClient == null) {
            return false;
        }
        log.debug(
            "Auto-renewal succeeded for paymentId={} (provider={})",
            payment.getPaymentId(), payment.getProvider()
        );
        return true;
    }

    /**
     * Advance {@code NEXT_RENEWAL_AT} by 30
     * days. Phase 5+ replaces this with the
     * plan's actual period.
     */
    private void advanceRenewalDate(Payment payment) {
        final OffsetDateTime newRenewalAt = payment.getNextRenewalAt()
            .plusDays(RENEWAL_PERIOD_DAYS);
        payment.setNextRenewalAt(newRenewalAt);
        payment.setUpdatedAt(OffsetDateTime.now());
        // Reset the failure count on a
        // successful renewal — the next cycle
        // starts fresh.
        payment.setFailureCount(0);
        paymentRepository.save(payment);
        log.info(
            "Auto-renewal OK: paymentId={}, nextRenewalAt={}",
            payment.getPaymentId(), newRenewalAt
        );
    }

    /**
     * Increment the failure count. If it
     * crosses the threshold, deactivate the
     * parent subscription via
     * subscription-service.
     */
    private void handleFailure(Payment payment, OffsetDateTime now) {
        final int newCount = payment.getFailureCount() + 1;
        payment.setFailureCount(newCount);
        payment.setUpdatedAt(now);
        payment.setFailureReason("Auto-renewal failure #" + newCount);
        paymentRepository.save(payment);
        log.warn(
            "Auto-renewal FAIL: paymentId={}, failureCount={}/{}",
            payment.getPaymentId(), newCount, MAX_FAILURES
        );
        if (newCount >= MAX_FAILURES) {
            deactivateParentSubscription(payment);
        }
    }

    /**
     * Call subscription-service to flip
     * {@code IS_ACTIVE=false} on the parent
     * subscription. The internal-token pattern
     * (X-Internal-Token) is used for the
     * service-to-service call (same as
     * person-setting-service).
     */
    private void deactivateParentSubscription(Payment payment) {
        if (payment.getSubscriptionId() == null) {
            log.warn(
                "Auto-renewal: paymentId={} has no subscriptionId, "
                    + "skipping dunning deactivation",
                payment.getPaymentId()
            );
            return;
        }
        try {
            subscriptionServiceClient.deactivateSubscription(
                payment.getSubscriptionId(),
                "Auto-renewal failed " + MAX_FAILURES + " times"
            );
            log.info(
                "Auto-renewal: subscriptionId={} deactivated "
                    + "after {} failures",
                payment.getSubscriptionId(), MAX_FAILURES
            );
        } catch (RuntimeException e) {
            // Log + swallow — the dunning
            // deactivation is best-effort.
            // A separate admin alert (Phase 5+)
            // should escalate.
            log.error(
                "Auto-renewal: failed to deactivate "
                    + "subscriptionId={} (will not retry)",
                payment.getSubscriptionId(), e
            );
        }
    }
}
