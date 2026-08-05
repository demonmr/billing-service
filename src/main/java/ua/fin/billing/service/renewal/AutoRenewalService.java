package ua.fin.billing.service.renewal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.fin.billing.client.liqpay.LiqPayCheckoutRequest;
import ua.fin.billing.client.liqpay.LiqPayClient;
import ua.fin.billing.client.stripe.StripeCheckoutRequest;
import ua.fin.billing.client.stripe.StripeClient;
import ua.fin.billing.config.BillingProperties;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;
import ua.fin.billing.repository.PaymentRepository;

import java.net.URI;
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
 *       original provider. Re-charge model:
 *       mint a FRESH hosted-checkout session
 *       (LiqPay {@code pay} action or Stripe
 *       Checkout Session), save a sibling
 *       {@code SUBSCRIPTION_PAYMENTS} row in
 *       {@code PENDING} state carrying the
 *       new {@code providerOrderId}, and
 *       let the existing webhook handler flip
 *       the sibling row to {@code SUCCEEDED}
 *       on callback. The renewal user pays via
 *       the same WebView flow as the initial
 *       checkout.</li>
 *   <li>On success: advance the original
 *       payment's {@code NEXT_RENEWAL_AT} by
 *       30 days and reset its failure count.</li>
 *   <li>On failure: increment
 *       {@code FAILURE_COUNT}. After 3 strikes
 *       call
 *       {@link SubscriptionServiceClient#deactivateSubscription}
 *       to flip the parent subscription
 *       {@code IS_ACTIVE=false}.</li>
 * </ol>
 *
 * <p>This is a "soft" auto-renewal: we mint a
 * new checkout URL each cycle (no stored
 * customer / payment-method IDs). Phase 5+
 * adds off-session renewals via
 * {@code stripe.PaymentIntent.create} with
 * stored {@code customer} +
 * {@code payment_method} parameters — the
 * webhook flow stays the same.</p>
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

    /**
     * Provider's renewal WebView URL templates.
     * LiqPay: 199.00 UAH; Stripe: 9.99 USD.
     * Phase 4.3 keeps the same placeholder
     * amounts as the initial checkout (Commit 3 /
     * 4); Phase 5+ resolves the amount from
     * subscription-service via the parent
     * subscription's plan id.
     */
    static final String LIQPAY_RENEWAL_AMOUNT = "199.00";
    static final String LIQPAY_RENEWAL_CURRENCY = "UAH";
    static final String STRIPE_RENEWAL_AMOUNT = "9.99";
    static final String STRIPE_RENEWAL_CURRENCY = "USD";

    private final PaymentRepository paymentRepository;
    private final LiqPayClient liqPayClient;
    private final StripeClient stripeClient;
    private final BillingProperties billingProperties;
    private final SubscriptionServiceClient subscriptionServiceClient;

    @Value("${billing.renewal.simulate-fail:false}")
    private boolean simulateFail;

    public AutoRenewalService(
        PaymentRepository paymentRepository,
        LiqPayClient liqPayClient,
        StripeClient stripeClient,
        BillingProperties billingProperties,
        SubscriptionServiceClient subscriptionServiceClient
    ) {
        this.paymentRepository = paymentRepository;
        this.liqPayClient = liqPayClient;
        this.stripeClient = stripeClient;
        this.billingProperties = billingProperties;
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
     * provider. Mints a fresh hosted-checkout
     * session for the original
     * {@code provider}, persists a sibling
     * PENDING {@code SUBSCRIPTION_PAYMENTS}
     * row carrying the new
     * {@code providerOrderId}, and returns
     * {@code true} on a successful provider
     * call.
     *
     * <p>If {@code simulate-fail} is set, the
     * method returns {@code false} WITHOUT
     * hitting the provider — used to test
     * dunning end-to-end without a live
     * LiqPay / Stripe account.</p>
     *
     * <p>On success, the sibling PENDING row
     * is picked up by the existing webhook
     * handler (LiqPay / Stripe), which
     * transitions it to {@code SUCCEEDED}.
     * The original payment row stays at
     * {@code SUCCEEDED}; only its
     * {@code NEXT_RENEWAL_AT} advances.</p>
     */
    boolean attemptRenewal(Payment payment) {
        if (simulateFail) {
            log.debug(
                "Auto-renewal simulated failure for paymentId={}",
                payment.getPaymentId()
            );
            return false;
        }
        final URI checkoutUrl;
        try {
            checkoutUrl = switch (payment.getProvider()) {
                case LIQPAY -> mintLiqPayRenewalSession(payment);
                case STRIPE -> mintStripeRenewalSession(payment);
            };
        } catch (RuntimeException e) {
            // Provider SDK threw (network, auth,
            // signature mismatch, ...). The caller
            // catches RuntimeException and
            // increments failureCount.
            throw e;
        }
        log.info(
            "Auto-renewal session minted: paymentId={}, provider={}, "
                + "renewalUrl={}",
            payment.getPaymentId(), payment.getProvider(), checkoutUrl
        );
        return true;
    }

    /**
     * Mint a fresh LiqPay checkout session for
     * a renewal payment. Persists a sibling
     * PENDING row carrying the new
     * {@code providerOrderId} so the webhook
     * handler can correlate the callback.
     */
    private URI mintLiqPayRenewalSession(Payment original) {
        final String renewalOrderId = original.getProviderOrderId()
            + "-renew-" + System.currentTimeMillis() + "-liqpay";

        final LiqPayCheckoutRequest req = new LiqPayCheckoutRequest(
            LiqPayCheckoutRequest.PROTOCOL_VERSION,
            billingProperties.liqpay().publicKey(),
            LiqPayCheckoutRequest.ACTION_PAY,
            new java.math.BigDecimal(LIQPAY_RENEWAL_AMOUNT),
            LIQPAY_RENEWAL_CURRENCY,
            "Renewal for plan " + original.getSubscriptionId(),
            renewalOrderId,
            billingProperties.liqpay().sandbox() ? 1 : 0,
            "https://example.com/liqpay/return",
            "https://example.com/rest/ua.fin.api/billing/webhooks/liqpay",
            "uk"
        );
        final URI url = liqPayClient.createCheckout(req);

        // Sibling PENDING row — the webhook
        // handler will transition it to
        // SUCCEEDED on LiqPay callback.
        saveSiblingPending(original, renewalOrderId, url);
        return url;
    }

    /**
     * Mint a fresh Stripe Checkout Session for
     * a renewal payment. Same sibling-row
     * pattern as LiqPay.
     */
    private URI mintStripeRenewalSession(Payment original) {
        final String renewalOrderId = original.getProviderOrderId()
            + "-renew-" + System.currentTimeMillis() + "-stripe";

        final StripeCheckoutRequest req = new StripeCheckoutRequest(
            original.getUserId(),
            // planId placeholder — Phase 5+
            // resolves from subscription-service
            // via original.getSubscriptionId().
            original.getSubscriptionId() != null
                ? original.getSubscriptionId()
                : java.util.UUID.randomUUID(),
            new java.math.BigDecimal(STRIPE_RENEWAL_AMOUNT),
            STRIPE_RENEWAL_CURRENCY,
            "Renewal for plan " + original.getSubscriptionId(),
            renewalOrderId,
            "https://example.com/stripe/return",
            "https://example.com/stripe/cancel"
        );
        final URI url = stripeClient.createCheckout(req);

        saveSiblingPending(original, renewalOrderId, url);
        return url;
    }

    /**
     * Persist a sibling PENDING payment row
     * carrying the renewal {@code orderId}.
     * The webhook handler resolves it via
     * {@code findByProviderOrderId} and flips
     * it to SUCCEEDED on the callback.
     */
    private void saveSiblingPending(
        Payment original, String renewalOrderId, URI checkoutUrl
    ) {
        final Payment sibling = Payment.builder()
            .userId(original.getUserId())
            .subscriptionId(original.getSubscriptionId())
            .provider(original.getProvider())
            .providerOrderId(renewalOrderId)
            .amount(original.getAmount())
            .currency(original.getCurrency())
            .status(PaymentStatus.PENDING)
            .description("Renewal for " + original.getPaymentId())
            .failureCount(0)
            .build();
        paymentRepository.saveAndFlush(sibling);
        log.debug(
            "Auto-renewal sibling PENDING row saved: originalId={}, "
                + "renewalId={}, orderId={}, checkoutUrl={}",
            original.getPaymentId(), sibling.getPaymentId(),
            renewalOrderId, checkoutUrl
        );
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
