package ua.fin.billing.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ua.fin.secure.lib.CurrentUser;
import ua.fin.billing.api.BillingApi;
import ua.fin.billing.entity.Invoice;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.model.CheckoutRequest;
import ua.fin.billing.model.CheckoutResponse;
import ua.fin.billing.repository.PaymentRepository;
import ua.fin.billing.service.CheckoutService;
import ua.fin.billing.service.CheckoutService.CheckoutResult;
import ua.fin.billing.service.InvoiceService;

import java.util.Optional;
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
    private final InvoiceService invoiceService;
    private final PaymentRepository paymentRepository;

    public BillingController(
        CheckoutService checkoutService,
        InvoiceService invoiceService,
        PaymentRepository paymentRepository
    ) {
        this.checkoutService = checkoutService;
        this.invoiceService = invoiceService;
        this.paymentRepository = paymentRepository;
    }

    @Override
    public ResponseEntity<CheckoutResponse> createCheckout(CheckoutRequest checkoutRequest) {
        final String userIdString = CurrentUser.getUserId();
        if (userIdString == null) {
            // No JWT in the request — should have been
            // rejected by the security filter. Return
            // 401 explicitly so the contract is honored.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final UUID userId = UUID.fromString(userIdString);
        final PaymentProvider provider = mapProvider(checkoutRequest.getProvider());

        // Commit 4: both providers wired. LiqPay via
        // LiqPayClient (Commit 3), Stripe via
        // StripeClient (this commit).
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

    /**
     * Phase 4.3 (Commit 5) — PDF invoice download.
     *
     * <p>The OpenAPI contract declares this
     * endpoint, but the generated
     * {@link BillingApi} interface doesn't have
     * it (binary {@code application/pdf}
     * responses are dropped by the generator).
     * This controller is the one true source
     * for the endpoint.</p>
     *
     * <p>Authorization: the requester must be
     * the payment's owner — otherwise 403. The
     * Spring Security filter chain (wired in
     * Commit 6) handles JWT validation; the
     * userId comparison happens here in the
     * controller.</p>
     */
    @GetMapping(
        value = "/rest/ua.fin.api/billing/invoices/{paymentId}/pdf",
        produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<byte[]> downloadInvoicePdf(
        @PathVariable("paymentId") UUID paymentId
    ) {
        // 1. Auth — read the JWT subject
        // (CurrentUser.getUserId is the static
        // utility from auth-lib).
        final String userIdString = CurrentUser.getUserId();
        if (userIdString == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final UUID requesterId = UUID.fromString(userIdString);

        // 2. Load the payment so we can
        // authorize. We need the userId on the
        // payment row to compare against the
        // JWT subject.
        final Optional<Payment> maybePayment = paymentRepository
            .findById(paymentId);
        if (maybePayment.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        final Payment payment = maybePayment.get();
        if (!payment.getUserId().equals(requesterId)) {
            log.warn(
                "Invoice download forbidden: requester={} != payer={}",
                requesterId, payment.getUserId()
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 3. Load the invoice PDF blob. 404 if
        // it hasn't been generated yet (e.g.
        // the webhook hasn't completed).
        final Optional<Invoice> maybeInvoice = invoiceService
            .findByPaymentId(paymentId);
        if (maybeInvoice.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        final Invoice invoice = maybeInvoice.get();

        // 4. Stream the bytes. The
        // Content-Disposition header tells the
        // browser to treat this as a download
        // (not as an inline PDF viewer) — the
        // mobile app will save the bytes
        // directly from the response body.
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData(
            "attachment",
            "invoice-" + invoice.getInvoiceNumber() + ".pdf"
        );
        return new ResponseEntity<>(invoice.getPdfBlob(), headers, HttpStatus.OK);
    }
}
