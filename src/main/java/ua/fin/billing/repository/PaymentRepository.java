package ua.fin.billing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4.3 — Spring Data JPA repository for
 * {@link Payment}. The composite of
 * {@link #findByProviderAndProviderOrderId(PaymentProvider, String)}
 * and
 * {@link #findByProviderAndProviderPaymentId(PaymentProvider, String)}
 * covers the two lookup paths the webhook handlers need:
 * the first when the provider sends our order id (LiqPay
 * case), the second when the provider sends its own
 * session id (Stripe case).
 *
 * <p>The auto-renewal scheduler (Commit 6) calls
 * {@link #findByStatusAndNextRenewalAtLessThanEqual(PaymentStatus, OffsetDateTime)}
 * to find payments due for re-charge.</p>
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Lookup by the order id we minted at checkout time.
     * Used by the LiqPay webhook (LiqPay sends our order id
     * in the callback).
     */
    Optional<Payment> findByProviderAndProviderOrderId(
        PaymentProvider provider, String providerOrderId
    );

    /**
     * Lookup by the provider's own payment / session id.
     * Used by the Stripe webhook (Stripe sends the
     * Checkout Session id, not our order id).
     */
    Optional<Payment> findByProviderAndProviderPaymentId(
        PaymentProvider provider, String providerPaymentId
    );

    /**
     * Auto-renewal scheduler: find all SUCCEEDED payments
     * whose next renewal date has passed. Sorted by
     * NEXT_RENEWAL_AT ASC so the oldest get charged first
     * (FIFO under a DB-enforced limit).
     */
    List<Payment> findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
        PaymentStatus status, OffsetDateTime cutoff
    );

    /**
     * User-facing history — list all of a user's payments
     * regardless of status. Used by the Phase 5+ payment
     * history endpoint.
     */
    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Authorization check for the {@code downloadInvoicePdf}
     * endpoint — given a paymentId, the controller loads the
     * payment, compares {@code userId} against the JWT
     * principal, and returns 403 on mismatch. The repository
     * itself does not enforce the check (no Spring Security
     * filter wired in Commit 2); the controller does the
     * comparison.
     */
    boolean existsByUserIdAndPaymentId(UUID userId, UUID paymentId);
}
