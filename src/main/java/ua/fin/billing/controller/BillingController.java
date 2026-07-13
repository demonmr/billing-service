package ua.fin.billing.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ua.fin.billing.api.BillingApi;
import ua.fin.billing.model.CheckoutRequest;
import ua.fin.billing.model.CheckoutResponse;

import java.net.URI;

/**
 * Phase 4.3 (commit 1) — STUB {@link BillingApi}
 * implementation. The generated {@code BillingApi}
 * interface (from the OpenAPI contract) declares
 * only the auth'd, JSON-bodied endpoints. The two
 * webhook endpoints (LiqPay + Stripe, un-auth'd,
 * form-encoded / raw body) and the binary PDF
 * download are NOT in the generated interface —
 * they have separate controllers
 * ({@link LiqPayWebhookController},
 * {@link StripeWebhookController}, and the
 * PDF endpoint becomes a method on this class in
 * commit 5).
 *
 * <p>Right now {@link #createCheckout} returns
 * {@code 501 Not Implemented} with a stub body.
 * The real implementation lands in commit 3
 * (LiqPay) and commit 4 (Stripe).</p>
 *
 * <p>The stub's reason for existing in commit 1 is
 * to prove the OpenAPI contract → Java interface
 * generation pipeline works. The Spring
 * application context is only buildable end-to-end
 * if the {@code BillingApi} interface is
 * implemented — the {@code apiNameSuffix=Api}
 * setting in pom.xml + the {@code interfaceOnly=true}
 * mode produce a pure interface; a missing
 * implementation would silently fail bean wiring
 * at startup.</p>
 */
@RestController
@Slf4j
public class BillingController implements BillingApi {

    @Override
    public ResponseEntity<CheckoutResponse> createCheckout(CheckoutRequest checkoutRequest) {
        log.warn(
            "createCheckout called for plan={}, provider={} but not yet implemented (commit 1 stub)",
            checkoutRequest != null ? checkoutRequest.getPlanId() : null,
            checkoutRequest != null ? checkoutRequest.getProvider() : null
        );
        // 501 with a stub URL. The frontend treats
        // 501 as "endpoint not yet available" and
        // surfaces a friendly "coming soon" message.
        // The OpenAPI generator emits a separate
        // `ProviderEnum` for each schema (Request vs
        // Response) even when the values are
        // identical, so we map them explicitly here.
        CheckoutResponse response = new CheckoutResponse()
            .paymentId(null)
            .checkoutUrl(URI.create("https://example.com/stub-not-implemented"));
        if (checkoutRequest != null && checkoutRequest.getProvider() != null) {
            response.setProvider(
                CheckoutResponse.ProviderEnum.valueOf(checkoutRequest.getProvider().name())
            );
        }
        return ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(response);
    }
}
