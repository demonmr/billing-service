package ua.fin.billing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.WebhookEvent;
import ua.fin.billing.entity.WebhookEventStatus;

import java.util.List;
import java.util.Optional;

/**
 * Phase 4.3 — Spring Data JPA repository for
 * {@link WebhookEvent}. The {@code (provider, eventId)} PK
 * powers the dedup mechanism: every webhook handler does
 * a {@code save()} first; PK violation → already
 * processed → 200 OK short-circuit.
 *
 * <p>{@link #findByProviderAndEventId(PaymentProvider, String)}
 * is the read path used by the monitoring endpoint
 * (Phase 5+) to look up "did we receive webhook X?".</p>
 */
@Repository
public interface WebhookEventRepository
        extends JpaRepository<WebhookEvent, WebhookEvent.WebhookEventId> {

    /**
     * Idempotent read: returns the event row if we've
     * already processed it. The controller's pre-insert
     * {@code save()} is the dedup gate; this method is
     * for status updates (IN_PROGRESS → COMPLETED) and
     * for monitoring.
     */
    Optional<WebhookEvent> findByProviderAndEventId(
        PaymentProvider provider, String eventId
    );

    /**
     * Operations: find stuck IN_PROGRESS rows older than
     * the sweeper threshold. Phase 5+ — the sweeper job
     * itself is also Phase 5+ (the docstring on
     * {@link WebhookEvent.WebhookEventId#provider} notes
     * the gap). The finder is provided here for
     * forward-compatibility.
     */
    List<WebhookEvent> findByStatusAndProviderOrderByReceivedAtAsc(
        WebhookEventStatus status, PaymentProvider provider
    );
}
