package ua.fin.billing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.fin.billing.entity.Invoice;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.pdf.InvoicePdfGenerator;
import ua.fin.billing.repository.InvoiceRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 4.3 (Commit 5) — invoice generation and
 * retrieval.
 *
 * <p>Generation flow: called from the
 * webhook handler (LiqPay / Stripe) on payment
 * success. Idempotent — if an invoice already
 * exists for the payment, the existing row is
 * returned untouched (re-deliveries shouldn't
 * produce a new invoice).</p>
 *
 * <p>Invoice number format:
 * {@code INV-YYYYMMDD-XXXXX} where XXXXX is a
 * 5-digit zero-padded sequence. The sequence is
 * held in an in-process {@link AtomicLong} reset
 * daily — adequate for a single billing-service
 * pod. Multi-pod uniqueness is enforced by the
 * {@code UNIQUE} constraint on
 * {@code BILLING_INVOICES.INVOICE_NUMBER}; on the
 * (rare) collision across pods, the second pod
 * retries with the next number.</p>
 */
@Service
@Slf4j
public class InvoiceService {

    private static final DateTimeFormatter DATE_SEGMENT =
        DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvoiceRepository invoiceRepository;
    private final InvoicePdfGenerator pdfGenerator;
    private final AtomicLong dailySequence = new AtomicLong(0);
    private volatile LocalDate currentDate = LocalDate.now();

    public InvoiceService(
        InvoiceRepository invoiceRepository,
        InvoicePdfGenerator pdfGenerator
    ) {
        this.invoiceRepository = invoiceRepository;
        this.pdfGenerator = pdfGenerator;
    }

    /**
     * Generate (or fetch the existing) invoice
     * for a successful payment. Idempotent.
     */
    @Transactional
    public Invoice generateOrFetch(Payment payment) {
        if (payment == null) {
            throw new IllegalArgumentException("payment is null");
        }
        // Idempotency: if the webhook fires twice
        // (LiqPay/Stripe re-delivery), don't
        // produce two invoices.
        final Optional<Invoice> existing = invoiceRepository
            .findByPaymentId(payment.getPaymentId());
        if (existing.isPresent()) {
            log.debug(
                "Invoice already exists for paymentId={}, returning existing",
                payment.getPaymentId()
            );
            return existing.get();
        }
        final String invoiceNumber = mintInvoiceNumber();
        final byte[] pdf = pdfGenerator.generate(payment, invoiceNumber);
        final Invoice invoice = Invoice.builder()
            .paymentId(payment.getPaymentId())
            .invoiceNumber(invoiceNumber)
            .pdfBlob(pdf)
            .build();
        final Invoice saved = invoiceRepository.save(invoice);
        log.info(
            "Generated invoice {} for paymentId={} ({} bytes)",
            invoiceNumber, payment.getPaymentId(), pdf.length
        );
        return saved;
    }

    /**
     * Look up an invoice by its paymentId. Used
     * by the {@code downloadInvoicePdf}
     * controller endpoint.
     */
    @Transactional(readOnly = true)
    public Optional<Invoice> findByPaymentId(UUID paymentId) {
        return invoiceRepository.findByPaymentId(paymentId);
    }

    /**
     * Mint a unique invoice number
     * {@code INV-YYYYMMDD-XXXXX}. Sequence resets
     * daily.
     */
    private String mintInvoiceNumber() {
        final LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            // Date rollover — reset the sequence.
            synchronized (dailySequence) {
                if (!today.equals(currentDate)) {
                    currentDate = today;
                    dailySequence.set(0);
                }
            }
        }
        final long seq = dailySequence.incrementAndGet();
        return "INV-"
            + today.format(DATE_SEGMENT)
            + "-"
            + String.format("%05d", seq);
    }
}
