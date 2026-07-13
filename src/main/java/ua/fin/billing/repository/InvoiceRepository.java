package ua.fin.billing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.fin.billing.entity.Invoice;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4.3 — Spring Data JPA repository for
 * {@link Invoice}. The {@code (paymentId) → invoice}
 * lookup is the read path for
 * {@code downloadInvoicePdf} (Commit 5).
 *
 * <p>The {@code invoiceNumber} lookup is reserved for
 * Phase 5+ (search-by-number endpoint). We don't use it
 * in Commit 5 — the controller goes paymentId → invoice.</p>
 */
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /**
     * The PDF download endpoint takes a {@code paymentId}
     * (the OpenAPI contract's path param) and resolves it
     * to the invoice blob. Returns empty when no invoice
     * has been generated yet (e.g. webhook hasn't completed
     * → controller returns 404).
     */
    Optional<Invoice> findByPaymentId(UUID paymentId);

    /**
     * Phase 5+ — search by human-readable invoice number.
     * Not exposed in Phase 4.3 but the finder is trivial to
     * keep around.
     */
    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    /**
     * Existence check used by the webhook handler
     * (Commit 5) to skip regeneration when an invoice
     * already exists for the payment (idempotency).
     */
    boolean existsByPaymentId(UUID paymentId);
}
