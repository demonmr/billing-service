// Unit test for Phase 4.3 (commit 1) — verifies the
// stub `BillingController` returns 501 for
// `createCheckout`. This is the only test that
// runs in commit 1; the real coverage (LiqPay,
// Stripe, PDF, scheduler) lands in subsequent
// commits.
//
// The two webhook endpoints and the PDF download
// are NOT in the generated `BillingApi` interface
// (they have `security: []` or binary content
// types in the contract) — they get separate
// controllers in commits 3, 4, and 5
// respectively. The `downloadInvoicePdf` test
// moves to commit 5's PDF test class.
//
// We use a plain `@WebMvcTest` (no
// `@SpringBootTest`) because the controller is
// pure — no DB, no service dependencies, no
// auth-lib resolution. The JWT validation lives
// in the security filter chain (configured in a
// later commit); for the stub we just want the
// controller's 501 behavior. The 401 case is
// covered in commit 6's `BillingControllerTest`
// once the security config is in place.

package ua.fin.billing.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingController.class)
class BillingControllerStubTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createCheckout_returns501() throws Exception {
        final String body = """
            {
              "planId": "00000000-0000-0000-0000-000000000001",
              "provider": "LIQPAY"
            }
            """;
        mockMvc.perform(post("/rest/ua.fin.api/billing/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.checkoutUrl").value(
                "https://example.com/stub-not-implemented"));
    }
}
