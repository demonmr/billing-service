package ua.fin.billing.entity;

/**
 * Phase 4.3 — payment lifecycle states.
 *
 * <ul>
 *   <li>{@link #PENDING} — checkout created, awaiting the
 *       provider's webhook (or a user-side timeout).</li>
 *   <li>{@link #SUCCEEDED} — webhook reported a successful
 *       charge. Triggers the END_DATE extension on
 *       subscription-service + invoice PDF generation.</li>
 *   <li>{@link #FAILED} — webhook reported a failure
 *       (declined card, signature mismatch, etc.). For
 *       auto-renewal attempts the {@code FAILURE_COUNT}
 *       counter drives the 3-strike dunning logic.</li>
 *   <li>{@link #REFUNDED} — Phase 5+ (no refund endpoint
 *       yet in Phase 4.3; the enum value is reserved).</li>
 * </ul>
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
