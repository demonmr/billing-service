package ua.fin.billing.client.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ua.fin.billing.config.BillingProperties;

/**
 * Phase 4.3 (Commit 4) — thin wrapper around
 * Stripe's {@link Webhook#constructEvent(String, String, String)}.
 *
 * <p>Why a dedicated verifier: the contract's
 * Stripe webhook controller must read the
 * {@code Stripe-Signature} header AND the RAW
 * request body (not a parsed {@code @RequestBody}
 * — Jackson re-orders whitespace and the HMAC
 * fails). This class is the single seam where
 * that happens, so the controller stays a thin
 * pass-through.</p>
 *
 * <p>Verification is delegated entirely to the
 * Stripe SDK. We do NOT re-implement HMAC-SHA256
 * — the SDK has the right tolerance window
 * (default 300s, controlled by the
 * {@code Stripe-Signature} timestamp prefix) and
 * is the path of least resistance.</p>
 */
@Component
@Slf4j
public class StripeWebhookVerifier {

    private final BillingProperties.Stripe stripeProperties;

    public StripeWebhookVerifier(BillingProperties billingProperties) {
        this.stripeProperties = billingProperties.stripe();
    }

    /**
     * Verify the {@code Stripe-Signature} header
     * against the raw body. Returns the typed
     * {@link Event} on success, throws
     * {@link StripeSignatureException} on failure.
     */
    public Event verifyAndParse(String rawBody, String signatureHeader) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new StripeSignatureException("Empty Stripe webhook body");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new StripeSignatureException("Missing Stripe-Signature header");
        }
        try {
            // The SDK throws
            // SignatureVerificationException on
            // a bad signature, an expired
            // timestamp, or a missing field in
            // the header. Anything else (e.g.
            // unknown event type) is a successful
            // verification — `constructEvent`
            // doesn't validate the type.
            final Event event = Webhook.constructEvent(
                rawBody,
                signatureHeader,
                stripeProperties.webhookSecret()
            );
            log.debug(
                "Verified Stripe webhook: type={}, id={}",
                event.getType(), event.getId()
            );
            return event;
        } catch (SignatureVerificationException e) {
            // Don't log the raw body — it can
            // contain customer data. The error
            // message from the SDK is safe.
            throw new StripeSignatureException(
                "Stripe signature verification failed: " + e.getMessage(), e
            );
        }
    }
}
