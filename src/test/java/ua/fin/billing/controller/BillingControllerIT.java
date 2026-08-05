// Integration test for Phase 4.3 (commits 3 + 4 + 5)
// — BillingController wired into the full Spring Boot
// context with H2 (SQLServer mode) for the DB layer.
//
// Upgraded from `@WebMvcTest` slice (commit 5) to
// `@SpringBootTest` so the assertions now exercise
// the real bean graph: REST controllers, security
// config (JWT filter bypassed via addFilters=false),
// JPA repositories, exception handlers, and the
// generated `BillingApi` interface wiring.
//
// The Stripe + LiqPay provider clients are still
// mocked (no live network). The webhook endpoints
// and the PDF download are NOT in the generated
// `BillingApi` interface (they have `security: []` or
// binary content types in the contract) — they get
// separate controllers in commits 3, 4, and 5
// respectively. Their tests live alongside their own
// controllers.
//
// What we cover:
//   1. createCheckout — LiqPay provider returns 201
//      with the checkout URL + paymentId + provider.
//   2. createCheckout — Stripe provider returns 201
//      (wired in Commit 4).
//   3. downloadInvoicePdf — happy path: requester is
//      the payer, invoice exists, returns PDF bytes.
//   4. downloadInvoicePdf — requester != payer → 403.
//   5. downloadInvoicePdf — payment exists, invoice
//      not generated → 404.

package ua.fin.billing.controller;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ua.fin.billing.entity.Invoice;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;
import ua.fin.billing.service.CheckoutService;
import ua.fin.secure.lib.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class BillingControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutService checkoutService;

    @MockitoBean
    private ua.fin.billing.service.InvoiceService invoiceService;

    @MockitoBean
    private ua.fin.billing.repository.PaymentRepository paymentRepository;

    private void authenticateAs(UUID userId) {
        // CurrentUser.getUserId() only resolves
        // through a JwtAuthenticationToken — the
        // test must populate SecurityContextHolder
        // with that exact type, otherwise the
        // controller returns 401.
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(
                "test-token",
                Jwts.claims().add("id", userId.toString()).build(),
                userId.toString(),
                java.util.List.of()
            )
        );
    }

    @Test
    void createCheckout_liqpay_returns201_withCheckoutUrl() throws Exception {
        // given
        final UUID paymentId = UUID.randomUUID();
        final UUID userId = UUID.randomUUID();
        authenticateAs(userId);
        when(checkoutService.checkout(
            any(UUID.class), any(UUID.class), eq(PaymentProvider.LIQPAY)
        )).thenReturn(new CheckoutService.CheckoutResult(
            paymentId,
            URI.create("https://www.liqpay.ua/api/3/checkout/abc123"),
            PaymentProvider.LIQPAY
        ));

        // when + then
        final String body = """
            {
              "planId": "00000000-0000-0000-0000-000000000001",
              "provider": "LIQPAY"
            }
            """;
        mockMvc.perform(post("/rest/ua.fin.api/billing/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
            .andExpect(jsonPath("$.checkoutUrl").value(
                "https://www.liqpay.ua/api/3/checkout/abc123"))
            .andExpect(jsonPath("$.provider").value("LIQPAY"));
    }

    @Test
    void createCheckout_stripe_returns201_withCheckoutUrl() throws Exception {
        // given — Commit 4 wired the Stripe branch.
        // The controller now returns 201 for STRIPE
        // too (it used to return 501 in Commit 3).
        final UUID paymentId = UUID.randomUUID();
        final UUID userId = UUID.randomUUID();
        authenticateAs(userId);
        when(checkoutService.checkout(
            any(UUID.class), any(UUID.class), eq(PaymentProvider.STRIPE)
        )).thenReturn(new CheckoutService.CheckoutResult(
            paymentId,
            URI.create("https://checkout.stripe.com/c/pay/cs_test_abc"),
            PaymentProvider.STRIPE
        ));

        // when + then
        final String body = """
            {
              "planId": "00000000-0000-0000-0000-000000000001",
              "provider": "STRIPE"
            }
            """;
        mockMvc.perform(post("/rest/ua.fin.api/billing/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
            .andExpect(jsonPath("$.checkoutUrl").value(
                "https://checkout.stripe.com/c/pay/cs_test_abc"))
            .andExpect(jsonPath("$.provider").value("STRIPE"));
    }

    // ----------------------------------------------------------------
    // Commit 5: PDF invoice download.
    // ----------------------------------------------------------------

    @Test
    void downloadInvoicePdf_happyPath_returnsPdfBytes() throws Exception {
        // given — requester is the payer,
        // invoice exists.
        final UUID userId = UUID.randomUUID();
        final UUID paymentId = UUID.randomUUID();
        final byte[] pdfBytes = "%PDF-1.4\n%fake-pdf\n".getBytes();
        authenticateAs(userId);
        final Payment payment = Payment.builder()
            .paymentId(paymentId)
            .userId(userId)
            .provider(PaymentProvider.LIQPAY)
            .providerOrderId("ord-1")
            .amount(new BigDecimal("199.00"))
            .currency("UAH")
            .status(PaymentStatus.SUCCEEDED)
            .failureCount(0)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
        final Invoice invoice = Invoice.builder()
            .invoiceId(UUID.randomUUID())
            .paymentId(paymentId)
            .invoiceNumber("INV-20260713-00001")
            .pdfBlob(pdfBytes)
            .generatedAt(OffsetDateTime.now())
            .build();
        when(paymentRepository.findById(paymentId))
            .thenReturn(Optional.of(payment));
        when(invoiceService.findByPaymentId(paymentId))
            .thenReturn(Optional.of(invoice));

        // when + then
        mockMvc.perform(get("/rest/ua.fin.api/billing/invoices/{id}/pdf",
                paymentId))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString(
                    "invoice-INV-20260713-00001.pdf")))
            .andExpect(content().bytes(pdfBytes));
    }

    @Test
    void downloadInvoicePdf_requesterMismatch_returns403() throws Exception {
        // given — requester is NOT the payer.
        final UUID requesterId = UUID.randomUUID();
        final UUID payerId = UUID.randomUUID(); // different
        final UUID paymentId = UUID.randomUUID();
        authenticateAs(requesterId);
        final Payment payment = Payment.builder()
            .paymentId(paymentId)
            .userId(payerId)
            .provider(PaymentProvider.STRIPE)
            .providerOrderId("ord-2")
            .amount(new BigDecimal("9.99"))
            .currency("USD")
            .status(PaymentStatus.SUCCEEDED)
            .failureCount(0)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
        when(paymentRepository.findById(paymentId))
            .thenReturn(Optional.of(payment));

        // when + then
        mockMvc.perform(get("/rest/ua.fin.api/billing/invoices/{id}/pdf",
                paymentId))
            .andExpect(status().isForbidden());
    }

    @Test
    void downloadInvoicePdf_invoiceNotGenerated_returns404() throws Exception {
        // given — payment exists, invoice
        // doesn't (webhook hasn't completed
        // yet).
        final UUID userId = UUID.randomUUID();
        final UUID paymentId = UUID.randomUUID();
        authenticateAs(userId);
        final Payment payment = Payment.builder()
            .paymentId(paymentId)
            .userId(userId)
            .provider(PaymentProvider.LIQPAY)
            .providerOrderId("ord-3")
            .amount(new BigDecimal("199.00"))
            .currency("UAH")
            .status(PaymentStatus.PENDING)
            .failureCount(0)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
        when(paymentRepository.findById(paymentId))
            .thenReturn(Optional.of(payment));
        when(invoiceService.findByPaymentId(paymentId))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/rest/ua.fin.api/billing/invoices/{id}/pdf",
                paymentId))
            .andExpect(status().isNotFound());
    }
}