package ua.fin.billing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentStatus;
import ua.fin.billing.repository.PaymentRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4.3 — payment state transitions + webhook processing.
 *
 * <p>Single source of truth for moving {@link Payment} rows
 * through the PENDING → SUCCEEDED / FAILED / REFUNDED
 * lifecycle. Called by:</p>
 * <ul>
 *   <li>{@link ua.fin.billing.controller.LiqPayWebhookController}
 *       on a LiqPay callback (Commit 3).</li>
 *   <li>{@link ua.fin.billing.controller.StripeWebhookController}
 *       on a Stripe callback (Commit 4).</li>
 *   <li>{@code AutoRenewalService} (Commit 6) when a
 *       re-charge succeeds or fails.</li>
 * </ul>
 */
@Service
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Mark a payment as SUCCEEDED. Sets
     * {@code providerPaymentId} (the provider's own id)
     * and {@code nextRenewalAt} (= now + 30 days, the
     * monthly billing cycle).
     */
    @Transactional
    public Payment markSucceeded(
        UUID paymentId,
        String providerPaymentId,
        String description
    ) {
        final Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setProviderPaymentId(providerPaymentId);
        if (description != null) {
            payment.setDescription(description);
        }
        payment.setNextRenewalAt(OffsetDateTime.now().plusDays(30));
        final Payment saved = paymentRepository.save(payment);
        log.info("Payment {} marked SUCCEEDED (provider paymentId={})",
            paymentId, providerPaymentId);
        return saved;
    }

    /**
     * Mark a payment as FAILED. Increments the
     * {@code failureCount} and stores the reason (truncated
     * to 500 chars to fit the column).
     */
    @Transactional
    public Payment markFailed(UUID paymentId, String reason) {
        final Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureCount(payment.getFailureCount() + 1);
        payment.setFailureReason(
            reason != null && reason.length() > 500
                ? reason.substring(0, 500)
                : reason
        );
        final Payment saved = paymentRepository.save(payment);
        log.warn("Payment {} marked FAILED (count={}, reason={})",
            paymentId, saved.getFailureCount(), reason);
        return saved;
    }

    /**
     * Look up a payment by the order id we minted at
     * checkout. Used by the LiqPay webhook handler (LiqPay
     * sends our order id in the callback, not the
     * provider's own id).
     */
    public Optional<Payment> findByProviderOrderId(
        ua.fin.billing.entity.PaymentProvider provider, String orderId
    ) {
        return paymentRepository.findByProviderAndProviderOrderId(provider, orderId);
    }

    /**
     * Look up a payment by the provider's own session /
     * payment id. Used by the Stripe webhook handler
     * (Stripe sends the Checkout Session id, not our
     * order id).
     */
    public Optional<Payment> findByProviderPaymentId(
        ua.fin.billing.entity.PaymentProvider provider, String providerPaymentId
    ) {
        return paymentRepository.findByProviderAndProviderPaymentId(
            provider, providerPaymentId
        );
    }

    /**
     * Thrown when a webhook references a paymentId that
     * doesn't exist in the DB. Maps to HTTP 400 in the
     * webhook controller (the provider will retry; we'll
     * re-verify on the next attempt).
     */
    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(UUID paymentId) {
            super("Payment not found: " + paymentId);
        }
    }
}
