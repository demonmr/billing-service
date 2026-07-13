package ua.fin.billing.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ua.fin.secure.lib.CurrentUser;
import ua.fin.billing.api.BillingApi;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.model.CheckoutRequest;
import ua.fin.billing.model.CheckoutResponse;
import ua.fin.billing.service.CheckoutService;
import ua.fin.billing.service.CheckoutService.CheckoutResult;

import java.util.UUID;

/**
 * Phase 4.3 (commit 3) — implements the generated
 * {@link BillingApi} interface. The
 * {@code createCheckout} endpoint is now real for
 * {@code provider=LIQPAY} (Commit 3); Stripe is wired
 * in Commit 4. The two webhook endpoints and the PDF
 * download are NOT in the generated interface — they
 * have separate controllers (see
 * {@link LiqPayWebhookController}, and
 * {@code StripeWebhookController} in Commit 4, plus
 * the {@code downloadInvoicePdf} method which lands
 * in Commit 5).
 *
 * <p>Auth: the auth-lib {@link CurrentUser} is a static
 * utility; we call it directly (no constructor
 * injection). The JWT filter populated by
 * auth-lib's {@code AuthLibSecurityConfig} sets the
 * thread-local on every authenticated request.</p>
 */
@RestController
@Slf4j
public class BillingController implements BillingApi {

    private final CheckoutService checkoutService;

    public BillingController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Override
    public ResponseEntity<CheckoutResponse> createCheckout(CheckoutRequest checkoutRequest) {
        final String userIdString = CurrentUser.getUserId();
        if (userIdString == null) {
            // No JWT in the request — should have been
            // rejected by the security filter (Commit 6
            // wires the security config). For the
            // open endpoints (Commit 1 stub) we return
            // 401 explicitly so the contract is honored.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final UUID userId = UUID.fromString(userIdString);
        final PaymentProvider provider = mapProvider(checkoutRequest.getProvider());

        if (provider == PaymentProvider.STRIPE) {
            // Commit 4 — return 501 until Stripe is wired in.
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        final CheckoutResult result = checkoutService.checkout(
            userId, checkoutRequest.getPlanId(), provider
        );
        final CheckoutResponse response = new CheckoutResponse()
            .paymentId(result.paymentId())
            .checkoutUrl(result.checkoutUrl())
            .provider(mapToResponseProvider(result.provider()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static PaymentProvider mapProvider(
        CheckoutRequest.ProviderEnum requestProvider
    ) {
        if (requestProvider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        return switch (requestProvider) {
            case LIQPAY -> PaymentProvider.LIQPAY;
            case STRIPE -> PaymentProvider.STRIPE;
        };
    }

    private static CheckoutResponse.ProviderEnum mapToResponseProvider(
        PaymentProvider provider
    ) {
        return switch (provider) {
            case LIQPAY -> CheckoutResponse.ProviderEnum.LIQPAY;
            case STRIPE -> CheckoutResponse.ProviderEnum.STRIPE;
        };
    }
}
