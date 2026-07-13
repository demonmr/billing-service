package ua.fin.billing.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;
import ua.fin.billing.entity.Invoice;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Phase 4.3 (Commit 5) — generates a UA/EN bilingual
 * invoice PDF for a successful {@link Payment} using
 * OpenPDF. Output is a byte array suitable for
 * storage in {@code BILLING_INVOICES.PDF_BLOB}
 * ({@code VARBINARY(MAX)}).
 *
 * <p>Template is intentionally minimal:
 * title, payment id, order id, plan description,
 * amount, currency, dates (created + paid),
 * provider, and a footer with the license
 * notice required by OpenPDF's LGPL.</p>
 *
 * <p>i18n is hardcoded UA/EN for Phase 4.3. Adding
 * more languages is Phase 6+ work — there's no
 * i18n library on the backend yet.</p>
 *
 * <p>No images, no fonts beyond OpenPDF's built-in
 * Helvetica — keeps the size around 5-15 KB per
 * invoice (the plan's expected range).</p>
 */
@Component
public class InvoicePdfGenerator {

    private static final DateTimeFormatter UA_DATE =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Kyiv"));
    private static final DateTimeFormatter EN_DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Europe/Kyiv"));

    /**
     * Render the invoice as a PDF byte array.
     * Caller is responsible for persisting the
     * result to {@link Invoice#getPdfBlob()}.
     */
    public byte[] generate(Payment payment, String invoiceNumber) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Document document = new Document();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            renderHeader(document, payment, invoiceNumber);
            renderPaymentBlock(document, payment);
            renderFooter(document);
            document.close();
        } catch (com.lowagie.text.DocumentException e) {
            // Wrap the checked DocumentException
            // (OpenPDF's API) into an unchecked one
            // — the controller maps it to 500.
            throw new IllegalStateException(
                "Failed to render invoice PDF for payment="
                    + payment.getPaymentId(), e
            );
        }
        return out.toByteArray();
    }

    private void renderHeader(
        Document document, Payment payment, String invoiceNumber
    ) throws com.lowagie.text.DocumentException {
        final Font titleFont = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 18f, Color.BLACK
        );
        final Paragraph title = new Paragraph(
            "АКТ / INVOICE", titleFont
        );
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        final Font normal = FontFactory.getFont(
            FontFactory.HELVETICA, 11f, Color.DARK_GRAY
        );
        final Paragraph numberLine = new Paragraph(
            "№ " + invoiceNumber, normal
        );
        numberLine.setAlignment(Element.ALIGN_CENTER);
        document.add(numberLine);

        final Paragraph dateLine = new Paragraph(
            "Дата видання / "
                + "Issue date: "
                + UA_DATE.format(payment.getUpdatedAt()) + " / "
                + EN_DATE.format(payment.getUpdatedAt()),
            normal
        );
        dateLine.setAlignment(Element.ALIGN_CENTER);
        document.add(dateLine);

        document.add(new Paragraph(" "));
    }

    private void renderPaymentBlock(
        Document document, Payment payment
    ) throws com.lowagie.text.DocumentException {
        final PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100f);
        table.setSpacingBefore(8f);

        final Font labelFont = FontFactory.getFont(
            FontFactory.HELVETICA_BOLD, 10f, Color.BLACK
        );
        final Font valueFont = FontFactory.getFont(
            FontFactory.HELVETICA, 10f, Color.DARK_GRAY
        );

        addRow(table, labelFont, valueFont,
            "Payment ID", payment.getPaymentId().toString());
        addRow(table, labelFont, valueFont,
            "Order ID", payment.getProviderOrderId());
        addRow(table, labelFont, valueFont,
            "Provider", formatProvider(payment.getProvider()));
        addRow(table, labelFont, valueFont,
            "Description", nullToEmpty(payment.getDescription()));
        addRow(table, labelFont, valueFont,
            "Amount", formatAmount(payment));
        addRow(table, labelFont, valueFont,
            "Status", payment.getStatus().name());
        addRow(table, labelFont, valueFont,
            "Created", UA_DATE.format(payment.getCreatedAt()));
        if (payment.getUpdatedAt() != null) {
            addRow(table, labelFont, valueFont,
                "Paid", UA_DATE.format(payment.getUpdatedAt()));
        }
        document.add(table);
    }

    private void addRow(
        PdfPTable table, Font labelFont, Font valueFont,
        String label, String value
    ) {
        final PdfPCell labelCell = new PdfPCell(
            new Paragraph(label, labelFont)
        );
        labelCell.setBackgroundColor(new Color(245, 245, 245));
        labelCell.setPadding(6f);
        final PdfPCell valueCell = new PdfPCell(
            new Paragraph(value != null ? value : "", valueFont)
        );
        valueCell.setPadding(6f);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void renderFooter(Document document)
        throws com.lowagie.text.DocumentException {
        document.add(new Paragraph(" "));
        final Font small = FontFactory.getFont(
            FontFactory.HELVETICA_OBLIQUE, 8f, Color.GRAY
        );
        final Paragraph footer = new Paragraph(
            "Generated by fin_app billing-service. "
                + "PDF rendered with OpenPDF (LGPL/MPL). "
                + "Issue date: " + OffsetDateTime.now()
                    .atZoneSameInstant(ZoneId.of("Europe/Kyiv"))
                    .format(EN_DATE) + ".",
            small
        );
        footer.setAlignment(Element.ALIGN_CENTER);
        document.add(footer);
    }

    private static String formatProvider(PaymentProvider provider) {
        return switch (provider) {
            case LIQPAY -> "LiqPay";
            case STRIPE -> "Stripe";
        };
    }

    private static String formatAmount(Payment payment) {
        return String.format(
            Locale.ROOT, "%s %s", payment.getAmount().toPlainString(),
            payment.getCurrency()
        );
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
