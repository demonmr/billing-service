package ua.fin.billing.entity;

/**
 * Phase 4.3 — payment provider enum. Mirrors the
 * {@code SUBSCRIPTION_PAYMENTS.PROVIDER} column
 * ('LIQPAY' | 'STRIPE') and the
 * {@code CheckoutRequest.provider} field in the OpenAPI
 * contract.
 *
 * <p>Stored as {@code STRING} (not ORDINAL) so the column
 * values are human-readable in the DB. Phase 5+ may add
 * {@code PAYPAL} / {@code APPLE_PAY} — the
 * {@link #name()} is the wire format.</p>
 */
public enum PaymentProvider {
    LIQPAY,
    STRIPE
}
