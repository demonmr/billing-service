package ua.fin.billing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 4.3 — BILLING_WEBHOOK_EVENTS row. The
 * <b>dedup</b> table for inbound provider webhooks.
 *
 * <p>PK is {@code (provider, eventId)} — every webhook
 * handler does an INSERT first. PK violation means
 * "we've already processed this event" and the handler
 * short-circuits to 200 OK without re-processing.
 * This is the only place we store the provider's
 * event id, so dedup is enforced at the DB layer
 * even under concurrent firings.</p>
 *
 * <p>The {@code paymentId} link is set after the dedup
 * insert + lock acquisition so monitoring can correlate
 * webhooks → payments. Nullable because the very first
 * row of a fresh deploy has no payment link yet.</p>
 */
@Entity
@Table(name = "BILLING_WEBHOOK_EVENTS")
@IdClass(WebhookEvent.WebhookEventId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "PROVIDER", nullable = false, length = 20)
    private PaymentProvider provider;

    @Id
    @Column(name = "EVENT_ID", nullable = false, length = 200)
    private String eventId;

    @Column(name = "EVENT_TYPE", length = 100)
    private String eventType;

    @Column(name = "RECEIVED_AT", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "PROCESSED_AT")
    private OffsetDateTime processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 20)
    private WebhookEventStatus status;

    @Column(name = "ERROR_MESSAGE", length = 2000)
    private String errorMessage;

    @Column(name = "PAYMENT_ID", columnDefinition = "UNIQUEIDENTIFIER")
    private UUID paymentId;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = WebhookEventStatus.IN_PROGRESS;
        }
    }

    /**
     * Composite PK class — required by {@code @IdClass}.
     * Field names + types must match the
     * {@code @Id}-annotated fields on the entity.
     */
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class WebhookEventId implements java.io.Serializable {
        private PaymentProvider provider;
        private String eventId;
    }
}
