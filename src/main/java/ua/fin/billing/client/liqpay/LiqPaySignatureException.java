package ua.fin.billing.client.liqpay;

/**
 * Phase 4.3 — thrown by {@link LiqPayClient} when a
 * webhook's signature doesn't match or the payload is
 * undecodable. Maps to HTTP 400 in the webhook controller.
 */
public class LiqPaySignatureException extends RuntimeException {

    public LiqPaySignatureException(String message) {
        super(message);
    }

    public LiqPaySignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
