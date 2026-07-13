// Unit test for Phase 4.3 (commit 5) — InvoicePdfGenerator
// pure-output test. We render an invoice for a fixture
// payment and assert:
//   1. the byte array is non-empty
//   2. it starts with the "%PDF-" magic bytes
//   3. the size is in the expected 1-100 KB range
//      (typical OpenPDF output is 3-15 KB for our
//      minimal template — wider range tolerated for
//      any future font/imagery changes)
//
// We do NOT assert the rendered text content
// (OpenPDF's text extraction API is unstable and
// locale-sensitive; the bytes-shape assertion is
// enough for a smoke test).

package ua.fin.billing.pdf;

import org.junit.jupiter.api.Test;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvoicePdfGeneratorTest {

    private final InvoicePdfGenerator generator = new InvoicePdfGenerator();

    @Test
    void generate_producesValidPdfBytes() {
        // given
        final Payment payment = Payment.builder()
            .paymentId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .provider(PaymentProvider.LIQPAY)
            .providerOrderId("ord-12345-liqpay")
            .amount(new BigDecimal("199.00"))
            .currency("UAH")
            .status(PaymentStatus.SUCCEEDED)
            .description("PRO plan")
            .failureCount(0)
            .createdAt(OffsetDateTime.now().minusMinutes(1))
            .updatedAt(OffsetDateTime.now())
            .build();

        // when
        final byte[] pdf = generator.generate(payment, "INV-20260713-00001");

        // then
        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(0);
        // PDF magic bytes
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // Size range — OpenPDF for our template
        // is typically 2-15 KB. Allow a wide
        // band to absorb font/encoding variance.
        assertThat((long) pdf.length)
            .as("PDF size should be in 1-100 KB range")
            .isBetween(1024L, 100L * 1024L);
    }
}
