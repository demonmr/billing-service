package ua.fin.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 4.3 — strongly-typed configuration for
 * billing-service. Bound from the {@code billing.*}
 * subtree in application.yml / env vars.
 *
 * <p>All secret fields ({@code liqpay.privateKey},
 * {@code stripe.apiKey}, {@code stripe.webhookSecret},
 * {@code subscriptionService.internalToken}) must be
 * supplied via env vars in production — the yml
 * has placeholder defaults for local dev only.</p>
 *
 * <p>Picked up by {@code @ConfigurationPropertiesScan}
 * on {@code BillingApplication} (no explicit
 * {@code @EnableConfigurationProperties} needed).</p>
 */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
    LiqPay liqpay,
    Stripe stripe,
    SubscriptionService subscriptionService,
    Scheduler scheduler
) {

    /**
     * LiqPay-specific settings. The {@code publicKey}
     * is safe to expose in the mobile app; the
     * {@code privateKey} is server-side only.
     */
    public record LiqPay(
        String publicKey,
        String privateKey,
        boolean sandbox
    ) {}

    /**
     * Stripe-specific settings. {@code apiKey} is the
     * secret key (sk_test_... or sk_live_...);
     * {@code webhookSecret} is the per-endpoint
     * signing secret (whsec_...).
     */
    public record Stripe(
        String apiKey,
        String webhookSecret,
        String apiVersion
    ) {}

    /**
     * Service-to-service target — billing-service
     * calls subscription-service on payment success
     * (to extend END_DATE) and on dunning failure
     * (to flip IS_ACTIVE=false). The internal token
     * is the {@code X-Internal-Token} header value
     * that auth-service / subscription-service
     * validates.
     */
    public record SubscriptionService(
        String baseUrl,
        String internalToken
    ) {}

    /**
     * Scheduler settings. The auto-renewal cron is
     * configurable so dev / smoke tests can fire the
     * job every 30 seconds instead of every day at
     * 03:00.
     */
    public record Scheduler(
        String autoRenewalCron
    ) {}
}
