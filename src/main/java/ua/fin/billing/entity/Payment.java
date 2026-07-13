package ua.fin.billing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 4.3 — SUBSCRIPTION_PAYMENTS row. One per checkout
 * attempt (PENDING on creation, then transitions to
 * SUCCEEDED / FAILED / REFUNDED on the webhook callback).
 *
 * <p>This is the source of truth for the LiqPay / Stripe
 * side of the billing flow. subscription-service keeps
 * the SUBSCRIPTIONS row separate — we only hold a
 * {@code subscriptionId} pointer (no cross-service FK; see
 * V23 migration notes).</p>
 *
 * <p>The {@code (provider, providerPaymentId)} pair is
 * unique — that's the dedup key the webhook handler uses
 * to detect replays. {@code providerOrderId} is OUR order
 * id (we mint it at checkout time and send it to the
 * provider); it must be globally unique per provider so the
 * checkout URL the mobile app receives is deterministic.</p>
 */
@Entity
@Table(name = "SUBSCRIPTION_PAYMENTS")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @Column(name = "PAYMENT_ID", columnDefinition = "UNIQUEIDENTIFIER")
    private UUID paymentId;

    @Column(name = "USER_ID", nullable = false, columnDefinition = "UNIQUEIDENTIFIER")
    private UUID userId;

    /**
     * Soft pointer to subscription-service's SUBSCRIPTIONS row.
     * No FK — different service's schema. The link is
     * application-level.
     */
    @Column(name = "SUBSCRIPTION_ID", columnDefinition = "UNIQUEIDENTIFIER")
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "PROVIDER", nullable = false, length = 20)
    private PaymentProvider provider;

    /**
     * Provider's own payment / session id — populated on
     * webhook arrival (LiqPay `payment_id`, Stripe
     * Checkout Session `id`). Nullable while PENDING.
     */
    @Column(name = "PROVIDER_PAYMENT_ID", length = 200)
    private String providerPaymentId;

    /**
     * Our own order id sent to the provider in the checkout
     * request. Globally unique per provider.
     * Format: `{uuid-no-dashes}-{provider-lowercase}` so the
     * mobile app receives a deterministic token.
     */
    @Column(name = "PROVIDER_ORDER_ID", nullable = false, length = 64)
    private String providerOrderId;

    @Column(name = "AMOUNT", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "CURRENCY", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;

    /**
     * Dunning counter — incremented by the auto-renewal
     * scheduler (Commit 6) on each failed re-charge. After
     * 3 strikes the scheduler flips the parent subscription's
     * IS_ACTIVE=false via a subscription-service call.
     */
    @Column(name = "FAILURE_COUNT", nullable = false)
    private Integer failureCount = 0;

    @Column(name = "FAILURE_REASON", length = 500)
    private String failureReason;

    /**
     * Set on success — the auto-renewal scheduler reads this
     * to find payments due for re-charge. Nullable for
     * one-off payments (PENDING/FAILED with no
     * auto-renewal intent).
     */
    @Column(name = "NEXT_RENEWAL_AT")
    private OffsetDateTime nextRenewalAt;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (paymentId == null) {
            paymentId = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (failureCount == null) {
            failureCount = 0;
        }
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
