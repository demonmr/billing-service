// Unit test for Phase 4.3 (commit 3) — verifies the
// wired `BillingController` returns 201 for LiqPay
// checkout + 501 for Stripe (until Commit 4). The real
// provider call is mocked out — the test only asserts
// the controller's behaviour (status code + body shape).
//
// The auth-lib `CurrentUser` is a static utility; the
// test does not exercise JWT auth (addFilters=false
// disables the security filter chain), and the
// checkoutService is fully mocked so the userId path
// in the controller is irrelevant to these assertions.
//
// The webhook endpoints and the PDF download are NOT
// in the generated `BillingApi` interface (they have
// `security: []` or binary content types in the
// contract) — they get separate controllers in
// commits 3, 4, and 5 respectively. Their tests live
// alongside their own controllers.

package ua.fin.billing.controller;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.service.CheckoutService;
import ua.fin.secure.lib.JwtAuthenticationToken;

import java.net.URI;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingController.class)
@AutoConfigureMockMvc(addFilters = false)
class BillingControllerStubTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutService checkoutService;

    @Test
    void createCheckout_liqpay_returns201_withCheckoutUrl() throws Exception {
        // given
        final UUID paymentId = UUID.randomUUID();
        final UUID userId = UUID.randomUUID();
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
    void createCheckout_stripe_returns501_untilCommit4() throws Exception {
        final UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(
                "test-token",
                Jwts.claims().add("id", userId.toString()).build(),
                userId.toString(),
                java.util.List.of()
            )
        );
        final String body = """
            {
              "planId": "00000000-0000-0000-0000-000000000001",
              "provider": "STRIPE"
            }
            """;
        mockMvc.perform(post("/rest/ua.fin.api/billing/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotImplemented());
    }
}
