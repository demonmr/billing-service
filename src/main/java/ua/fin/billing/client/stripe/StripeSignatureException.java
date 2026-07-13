package ua.fin.billing.client.stripe;

/**
 * Phase 4.3 (Commit 4) — thrown by
 * {@link StripeWebhookVerifier} when the
 * {@code Stripe-Signature} header does not verify
 * against the configured webhook secret, or when
 * the event payload cannot be parsed. Mirrors
 * {@link ua.fin.billing.client.liqpay.LiqPaySignatureException}
 * for the LiqPay side.
 */
public class StripeSignatureException extends RuntimeException {

    public StripeSignatureException(String message) {
        super(message);
    }

    public StripeSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
