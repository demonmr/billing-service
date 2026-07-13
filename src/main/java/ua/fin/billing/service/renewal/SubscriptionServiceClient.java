package ua.fin.billing.service.renewal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ua.fin.billing.config.BillingProperties;

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
@Component
@Slf4j
public class SubscriptionServiceClient {

    private final RestClient restClient;
    private final BillingProperties.SubscriptionService properties;

    public SubscriptionServiceClient(BillingProperties billingProperties) {
        this.properties = billingProperties.subscriptionService();
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .defaultHeader(
                HttpHeaders.CONTENT_TYPE,
                "application/json"
            )
            .build();
    }

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
        try {
            restClient.post()
                .uri("/rest/ua.fin.api/subscriptions/internal/{id}/deactivate",
                    subscriptionId)
                .header("X-Internal-Token", properties.internalToken())
                .body(java.util.Map.of("reason", reason))
                .retrieve()
                .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException
                     | org.springframework.web.client.HttpServerErrorException e) {
            // 4xx / 5xx — log and propagate so
            // the caller (AutoRenewalService) can
            // decide whether to retry.
            log.error(
                "subscription-service: deactivation failed: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString()
            );
            throw new RuntimeException(
                "subscription-service deactivation failed: "
                    + e.getStatusCode(), e
            );
        }
    }
}
