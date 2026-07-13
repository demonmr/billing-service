package ua.fin.billing.controller;

import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.fin.billing.client.stripe.StripeSignatureException;
import ua.fin.billing.client.stripe.StripeWebhookVerifier;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.WebhookEvent;
import ua.fin.billing.repository.PaymentRepository;
import ua.fin.billing.service.WebhookService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Phase 4.3 (Commit 4) — Stripe webhook endpoint.
 *
 * <p>Un-authenticated (no JWT) — security is
 * provided by the {@code Stripe-Signature} header
 * (HMAC-SHA256 of the raw body, with timestamp
 * tolerance). The path matches the OpenAPI
 * contract but is NOT on the generated
 * {@code BillingApi} interface (the generator
 * drops endpoints with {@code security: []}).</p>
 *
 * <p>Critical: the raw request body MUST be read
 * once via {@code request.getInputStream()} and
 * reused for signature verification. If a
 * {@code @RequestBody String} parameter is used
 * instead, Jackson can re-order whitespace and
 * the HMAC fails. This is a known Stripe +
 * Spring integration gotcha.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Read the raw body, verify the
 *       {@code Stripe-Signature} header. On
 *       failure → 400 (Stripe will not retry — the
 *       signature will never match).</li>
 *   <li>Try to claim the event id
 *       ({@code event.getId()}) via
 *       {@link WebhookService#tryClaim}. On
 *       duplicate → 200 OK.</li>
 *   <li>Handle the event: for
 *       {@code checkout.session.completed} and
 *       {@code checkout.session.async_payment_succeeded},
 *       look up the matching
 *       {@code SUBSCRIPTION_PAYMENTS} row by the
 *       {@code metadata.order_id} we embedded at
 *       checkout, and transition the row to
 *       SUCCEEDED. For
 *       {@code checkout.session.async_payment_failed},
 *       mark FAILED. Other event types are
 *       acknowledged (200 OK) without action.</li>
 *   <li>Mark the webhook event COMPLETED.</li>
 * </ol>
 */
@RestController
@RequestMapping("/rest/ua.fin.api/billing/webhooks")
@Slf4j
public class StripeWebhookController {

    private final StripeWebhookVerifier stripeWebhookVerifier;
    private final WebhookService webhookService;
    private final PaymentRepository paymentRepository;

    public StripeWebhookController(
        StripeWebhookVerifier stripeWebhookVerifier,
        WebhookService webhookService,
        PaymentRepository paymentRepository
    ) {
        this.stripeWebhookVerifier = stripeWebhookVerifier;
        this.webhookService = webhookService;
        this.paymentRepository = paymentRepository;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Void> handleStripe(
        @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader,
        HttpServletRequest request
    ) {
        // 1. Read the raw body ONCE — Jackson
        // would re-order whitespace and break the
        // HMAC. We read the bytes here and pass
        // them as a String to the verifier.
        final String rawBody;
        try {
            rawBody = new String(
                request.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            log.warn("Stripe webhook body read failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 2. Verify the signature. Throws on
        // mismatch → 400.
        final Event event;
        try {
            event = stripeWebhookVerifier.verifyAndParse(rawBody, signatureHeader);
        } catch (StripeSignatureException e) {
            log.warn("Stripe webhook signature rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 3. Dedup via Stripe's event id.
        final String eventId = event.getId();
        final String eventType = event.getType();
        final Optional<WebhookEvent> claim = webhookService.tryClaim(
            PaymentProvider.STRIPE, eventId, eventType
        );
        if (claim.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        // 4. Dispatch based on event type. We
        // only care about the success / failure
        // events for a Checkout Session; other
        // types (charge.refunded, customer.created,
        // ...) are acknowledged without action.
        try {
            switch (eventType) {
                case "checkout.session.completed",
                     "checkout.session.async_payment_succeeded" -> {
                    handleSucceeded(event, claim.get());
                }
                case "checkout.session.async_payment_failed",
                     "checkout.session.expired" -> {
                    handleFailed(event, claim.get());
                }
                default -> {
                    log.debug(
                        "Stripe event {} ignored (unhandled type)",
                        eventType
                    );
                    webhookService.markCompleted(claim.get(), null);
                }
            }
        } catch (RuntimeException e) {
            webhookService.markFailed(claim.get(), e.getMessage());
            throw e;
        }

        return ResponseEntity.ok().build();
    }

    private void handleSucceeded(Event event, WebhookEvent claim) {
        // The event's `data.object` is the
        // deserialized Stripe object — for
        // checkout.session.completed, it's a
        // Session. The session's metadata holds
        // the orderId we minted at checkout.
        final Session session = deserializeSession(event);
        final String orderId = session.getMetadata().get("order_id");
        if (orderId == null) {
            // A session without our orderId
            // metadata is a foreign / test
            // event — log and ack.
            log.warn(
                "Stripe checkout.session.completed has no order_id metadata (session={})",
                session.getId()
            );
            webhookService.markCompleted(claim, null);
            return;
        }
        final Optional<Payment> maybePayment = paymentRepository
            .findByProviderAndProviderOrderId(PaymentProvider.STRIPE, orderId);
        if (maybePayment.isEmpty()) {
            log.warn(
                "Stripe webhook for unknown orderId={} (session={})",
                orderId, session.getId()
            );
            webhookService.markFailed(
                claim, "No matching SUBSCRIPTION_PAYMENTS row for orderId=" + orderId
            );
            return;
        }
        final Payment payment = maybePayment.get();
        payment.setStatus(ua.fin.billing.entity.PaymentStatus.SUCCEEDED);
        payment.setProviderPaymentId(session.getId());
        paymentRepository.save(payment);
        webhookService.markCompleted(claim, payment.getPaymentId());
        log.info(
            "Stripe payment {} marked SUCCEEDED (orderId={}, sessionId={})",
            payment.getPaymentId(), orderId, session.getId()
        );
    }

    private void handleFailed(Event event, WebhookEvent claim) {
        final Session session = deserializeSession(event);
        final String orderId = session.getMetadata().get("order_id");
        if (orderId == null) {
            log.warn(
                "Stripe {} has no order_id metadata (session={})",
                event.getType(), session.getId()
            );
            webhookService.markCompleted(claim, null);
            return;
        }
        final Optional<Payment> maybePayment = paymentRepository
            .findByProviderAndProviderOrderId(PaymentProvider.STRIPE, orderId);
        if (maybePayment.isEmpty()) {
            log.warn(
                "Stripe webhook for unknown orderId={} (session={})",
                orderId, session.getId()
            );
            webhookService.markFailed(
                claim, "No matching SUBSCRIPTION_PAYMENTS row for orderId=" + orderId
            );
            return;
        }
        final Payment payment = maybePayment.get();
        payment.setStatus(ua.fin.billing.entity.PaymentStatus.FAILED);
        payment.setFailureCount(payment.getFailureCount() + 1);
        paymentRepository.save(payment);
        webhookService.markCompleted(claim, payment.getPaymentId());
        log.info(
            "Stripe payment {} marked FAILED (orderId={}, sessionId={})",
            payment.getPaymentId(), orderId, session.getId()
        );
    }

    /**
     * Extract the {@link Session} object from
     * the Stripe event's data field. The SDK
     * gives us a deserialized object based on
     * the event type.
     */
    private static Session deserializeSession(Event event) {
        return (Session) event.getDataObjectDeserializer()
            .getObject()
            .orElseThrow(() -> new IllegalStateException(
                "Stripe event " + event.getId()
                    + " has no deserializable data.object"
            ));
    }
}
