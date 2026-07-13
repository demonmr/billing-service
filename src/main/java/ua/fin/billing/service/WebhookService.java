package ua.fin.billing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.WebhookEvent;
import ua.fin.billing.entity.WebhookEventStatus;
import ua.fin.billing.repository.WebhookEventRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Phase 4.3 — webhook dedup gate.
 *
 * <p>Every webhook handler (LiqPay, Stripe) calls
 * {@link #tryClaim(PaymentProvider, String, String)} FIRST
 * with the provider's event id. If the row is inserted, the
 * handler proceeds with the work. If the insert fails (PK
 * conflict on {@code (provider, event_id)}), it means
 * we've already processed this event — the handler
 * short-circuits to 200 OK without re-processing.</p>
 *
 * <p>The dedup is enforced at the DB layer (PK constraint
 * on {@code BILLING_WEBHOOK_EVENTS}), so concurrent webhook
 * firings can be processed by the same service or multiple
 * pods of billing-service without race conditions.</p>
 */
@Service
@Slf4j
public class WebhookService {

    private final WebhookEventRepository webhookEventRepository;

    public WebhookService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    /**
     * Try to claim ownership of a webhook event. Returns
     * empty if the event has already been processed
     * (the handler should short-circuit to 200 OK).
     * Returns the newly-inserted row on success — the
     * handler should then process the payment transition
     * and call {@link #markCompleted} on success or
     * {@link #markFailed} on error.
     *
     * <p>Implementation: try the insert; if it throws a
     * data-integrity violation (PK conflict), return
     * empty. The transaction propagation is REQUIRED so
     * the failed insert rolls back cleanly.</p>
     */
    @Transactional
    public Optional<WebhookEvent> tryClaim(
        PaymentProvider provider, String eventId, String eventType
    ) {
        if (eventId == null || eventId.isBlank()) {
            // A missing event id means we can't dedup —
            // log and proceed (best-effort).
            log.warn(
                "Webhook for provider={} has empty eventId; skipping dedup",
                provider
            );
            return Optional.of(WebhookEvent.builder()
                .provider(provider)
                .eventId("__missing__")
                .eventType(eventType)
                .receivedAt(OffsetDateTime.now())
                .status(WebhookEventStatus.IN_PROGRESS)
                .build());
        }
        try {
            final WebhookEvent row = WebhookEvent.builder()
                .provider(provider)
                .eventId(eventId)
                .eventType(eventType)
                .receivedAt(OffsetDateTime.now())
                .status(WebhookEventStatus.IN_PROGRESS)
                .build();
            final WebhookEvent saved = webhookEventRepository.saveAndFlush(row);
            log.debug(
                "Claimed webhook event: provider={}, eventId={}, type={}",
                provider, eventId, eventType
            );
            return Optional.of(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info(
                "Webhook event already processed: provider={}, eventId={} — short-circuiting to 200 OK",
                provider, eventId
            );
            return Optional.empty();
        }
    }

    @Transactional
    public void markCompleted(WebhookEvent event, java.util.UUID paymentId) {
        event.setStatus(WebhookEventStatus.COMPLETED);
        event.setProcessedAt(OffsetDateTime.now());
        event.setPaymentId(paymentId);
        webhookEventRepository.save(event);
    }

    @Transactional
    public void markFailed(WebhookEvent event, String errorMessage) {
        event.setStatus(WebhookEventStatus.FAILED);
        event.setProcessedAt(OffsetDateTime.now());
        event.setErrorMessage(
            errorMessage != null && errorMessage.length() > 2000
                ? errorMessage.substring(0, 2000)
                : errorMessage
        );
        webhookEventRepository.save(event);
    }
}
