package ua.fin.billing.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.fin.billing.client.liqpay.LiqPayClient;
import ua.fin.billing.client.liqpay.LiqPaySignatureException;
import ua.fin.billing.client.liqpay.LiqPayWebhookPayload;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.WebhookEvent;
import ua.fin.billing.service.InvoiceService;
import ua.fin.billing.service.PaymentService;
import ua.fin.billing.service.WebhookService;

import java.util.Optional;

/**
 * Phase 4.3 (Commit 3) — LiqPay webhook endpoint.
 *
 * <p>Un-authenticated (no JWT) — security is provided by
 * the HMAC-SHA1 signature on the {@code data} +
 * {@code signature} form fields. The path is the same as
 * in the OpenAPI contract but <b>not</b> on the
 * generated {@code BillingApi} interface (the generator
 * drops endpoints that have {@code security: []} or
 * form-encoded bodies). This controller is the one true
 * source of the endpoint.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Decode + verify the {@code data} + {@code signature}.
 *       On mismatch → 400 (LiqPay will not retry — the
 *       signature will never match).</li>
 *   <li>Try to claim the event id (LiqPay sends our
 *       {@code order_id} in the payload) via
 *       {@link WebhookService#tryClaim}. On duplicate →
 *       200 OK without re-processing.</li>
 *   <li>Look up the matching {@code SUBSCRIPTION_PAYMENTS}
 *       row by {@code order_id}. On missing → 200 OK
 *       (LiqPay will retry once the row is created — we
 *       log the orphan for monitoring).</li>
 *   <li>Transition the payment to SUCCEEDED / FAILED on
 *       the payload status. Mark the webhook event
 *       COMPLETED.</li>
 * </ol>
 */
@RestController
@RequestMapping("/rest/ua.fin.api/billing/webhooks")
@Slf4j
public class LiqPayWebhookController {

    private final LiqPayClient liqPayClient;
    private final PaymentService paymentService;
    private final WebhookService webhookService;
    private final InvoiceService invoiceService;

    public LiqPayWebhookController(
        LiqPayClient liqPayClient,
        PaymentService paymentService,
        WebhookService webhookService,
        InvoiceService invoiceService
    ) {
        this.liqPayClient = liqPayClient;
        this.paymentService = paymentService;
        this.webhookService = webhookService;
        this.invoiceService = invoiceService;
    }

    @PostMapping(
        value = "/liqpay",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<Void> handleLiqPay(
        @RequestParam("data") String data,
        @RequestParam("signature") String signature
    ) {
        // 1. Decode + verify. Throws on mismatch → 400.
        final LiqPayWebhookPayload payload;
        try {
            payload = liqPayClient.decodeAndVerifyWebhook(data, signature);
        } catch (LiqPaySignatureException e) {
            log.warn("LiqPay webhook signature rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 2. Dedup via order_id (LiqPay's "event id" in
        // Phase 4.3 is the order_id we minted at checkout).
        final String eventId = payload.orderId();
        final Optional<WebhookEvent> claim = webhookService.tryClaim(
            PaymentProvider.LIQPAY, eventId, "callback"
        );
        if (claim.isEmpty()) {
            // Already processed.
            return ResponseEntity.ok().build();
        }

        // 3. Find the payment by our order_id.
        final Optional<Payment> maybePayment = paymentService
            .findByProviderOrderId(PaymentProvider.LIQPAY, eventId);
        if (maybePayment.isEmpty()) {
            log.warn(
                "LiqPay webhook for unknown orderId={} (payload status={})",
                eventId, payload.status()
            );
            webhookService.markFailed(
                claim.get(), "No matching SUBSCRIPTION_PAYMENTS row for orderId=" + eventId
            );
            // 200 OK — the row may not exist yet if the
            // checkout was created on a different pod and
            // hasn't replicated. LiqPay will retry.
            return ResponseEntity.ok().build();
        }
        final Payment payment = maybePayment.get();

        // 4. State transition.
        try {
            if (payload.isSuccess()) {
                paymentService.markSucceeded(
                    payment.getPaymentId(),
                    payload.paymentId() != null
                        ? String.valueOf(payload.paymentId())
                        : null,
                    payload.description()
                );
                // Commit 5: generate the invoice
                // PDF + persist. Idempotent — a
                // re-delivery returns the existing
                // invoice. Failures here are
                // non-fatal: the payment is already
                // SUCCEEDED, we log + ack so LiqPay
                // doesn't retry forever.
                try {
                    invoiceService.generateOrFetch(payment);
                } catch (RuntimeException e) {
                    log.error(
                        "Invoice generation failed for paymentId={} (payment still SUCCEEDED)",
                        payment.getPaymentId(), e
                    );
                }
                webhookService.markCompleted(claim.get(), payment.getPaymentId());
                log.info(
                    "LiqPay payment {} marked SUCCEEDED (orderId={}, providerPaymentId={})",
                    payment.getPaymentId(), eventId, payload.paymentId()
                );
            } else if (payload.isFailure()) {
                paymentService.markFailed(
                    payment.getPaymentId(),
                    payload.errDescription() != null
                        ? payload.errDescription()
                        : "LiqPay status=" + payload.status()
                );
                webhookService.markCompleted(claim.get(), payment.getPaymentId());
                log.info(
                    "LiqPay payment {} marked FAILED (orderId={}, err={})",
                    payment.getPaymentId(), eventId, payload.errCode()
                );
            } else {
                // Unknown status — log and ack to avoid LiqPay
                // retries (we don't know what to do).
                log.warn(
                    "LiqPay payment {} status={} (unhandled); ack 200",
                    payment.getPaymentId(), payload.status()
                );
                webhookService.markCompleted(claim.get(), payment.getPaymentId());
            }
        } catch (RuntimeException e) {
            webhookService.markFailed(claim.get(), e.getMessage());
            // Re-throw so the global exception handler
            // returns 500 — LiqPay will retry.
            throw e;
        }

        return ResponseEntity.ok().build();
    }
}
