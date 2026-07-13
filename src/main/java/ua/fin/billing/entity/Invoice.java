package ua.fin.billing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 4.3 — BILLING_INVOICES row. 1:1 with a successful
 * {@link Payment} (created by the webhook handler in
 * Commit 5 after the OpenPDF generator produces the PDF
 * blob).
 *
 * <p>The PDF is stored in {@code VARBINARY(MAX)} —
 * 5-50 KB per invoice, ≤50 MB/day at peak. Phase 5+ may
 * move to Azure Blob if volume grows. The
 * {@code invoiceNumber} is the human-readable identifier
 * formatted {@code INV-YYYYMMDD-XXXXX} (5-digit zero-padded
 * sequence); globally unique.</p>
 */
@Entity
@Table(name = "BILLING_INVOICES")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @Column(name = "INVOICE_ID", columnDefinition = "UNIQUEIDENTIFIER")
    private UUID invoiceId;

    /**
     * Soft pointer to the parent payment. No FK — we
     * resolve the relation via the service layer. The
     * payment's {@code userId} is the same as the
     * invoice's payer (used by the
     * {@code downloadInvoicePdf} endpoint to enforce
     * 403 on cross-user access).
     */
    @Column(name = "PAYMENT_ID", nullable = false, columnDefinition = "UNIQUEIDENTIFIER")
    private UUID paymentId;

    @Column(name = "INVOICE_NUMBER", nullable = false, length = 50, unique = true)
    private String invoiceNumber;

    /**
     * OpenPDF output. {@code @Lob} + {@code BLOB} maps to
     * SQL Server's {@code VARBINARY(MAX)}.
     */
    @Lob
    @Column(name = "PDF_BLOB", nullable = false, columnDefinition = "VARBINARY(MAX)")
    private byte[] pdfBlob;

    @Column(name = "GENERATED_AT", nullable = false)
    private OffsetDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        if (invoiceId == null) {
            invoiceId = UUID.randomUUID();
        }
        if (generatedAt == null) {
            generatedAt = OffsetDateTime.now();
        }
    }
}
