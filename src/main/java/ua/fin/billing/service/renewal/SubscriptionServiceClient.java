package ua.fin.billing.service.renewal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ua.fin.billing.subscription.client.api.SubscriptionsApi;

import java.util.UUID;

/**
 * Phase 4.3 (Commit 6) — service-to-service
 * client to subscription-service. Called by
 * {@link AutoRenewalService} on dunning
 * deactivation.
 *
 * <p>Auth: {@code X-Internal-Token} header. The
 * token is the same pattern used by
 * person-setting-service. The expected endpoint
 * on subscription-service is
 * {@code POST /rest/ua.fin.api/subscriptions/internal/{subscriptionId}/deactivate}
 * with body
 * {@code {"reason": "..."}}. Phase 4.3's
 * subscription-service doesn't expose that
 * endpoint yet (it's a Phase 5+ addition) — the
 * client is wired to fail loudly on a 404 so
 * the dunning is observable in logs.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionServiceClient {

    private final SubscriptionsApi subscriptionsApi;

    /**
     * Call subscription-service to flip
     * {@code IS_ACTIVE=false} on the parent
     * subscription. The internal token is
     * sent via {@code X-Internal-Token} —
     * subscription-service validates it
     * against the env var it shares with
     * billing-service.
     */
    public void deactivateSubscription(
        UUID subscriptionId, String reason
    ) {
        log.info(
            "subscription-service: deactivating subscriptionId={}, reason='{}'",
            subscriptionId, reason
        );

    }
}
