package ua.fin.billing.entity;

/**
 * Phase 4.3 — webhook processing lifecycle.
 *
 * <ul>
 *   <li>{@link #IN_PROGRESS} — row inserted at the start of
 *       webhook processing. If the JVM crashes mid-flight,
 *       a follow-up replay finds the row still IN_PROGRESS
 *       and we have a problem (Phase 5+ adds a sweeper
 *       job to reset stuck IN_PROGRESS rows after 1h).</li>
 *   <li>{@link #COMPLETED} — webhook processed
 *       successfully. The {@code paymentId} is set and the
 *       status transition (PENDING → SUCCEEDED / FAILED)
 *       has been committed.</li>
 *   <li>{@link #FAILED} — webhook processing raised an
 *       error after the dedup insert. The provider will
 *       retry (LiqPay does, Stripe does). We do not flip
 *       the payment status in this case — the next retry
 *       is the source of truth.</li>
 * </ul>
 */
public enum WebhookEventStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
