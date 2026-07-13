package ua.fin.billing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ua.fin.billing.client.liqpay.LiqPayCheckoutRequest;
import ua.fin.billing.client.liqpay.LiqPayClient;
import ua.fin.billing.client.stripe.StripeCheckoutRequest;
import ua.fin.billing.client.stripe.StripeClient;
import ua.fin.billing.config.BillingProperties;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;
import ua.fin.billing.repository.PaymentRepository;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

/**
 * Phase 4.3 — orchestrates the checkout flow.
 *
 * <p>Steps (LiqPay case, Commit 3; Stripe case, Commit 4):</p>
 * <ol>
 *   <li>Mint a {@code providerOrderId} (our own ID, globally
 *       unique per provider — UUID + provider suffix).</li>
 *   <li>Create a {@code SUBSCRIPTION_PAYMENTS} row with
 *       {@code STATUS=PENDING}. The row's existence is the
 *       contract that lets the webhook handler update
 *       {@code STATUS=SUCCEEDED} on a LiqPay/Stripe callback.</li>
 *   <li>Call the chosen provider to mint a hosted-checkout
 *       URL.</li>
 *   <li>Return the URL + the paymentId so the mobile app
 *       can poll {@code GET /payments/{id}} (Phase 5+) or
 *       rely on the FCM push (Phase 4.9c) for the outcome.</li>
 * </ol>
 *
 * <p>For Phase 4.3 we hardcode the plan price — the
 * service-to-service call to subscription-service to
 * resolve the plan lands in Commit 4. The hardcoded
 * placeholder is wired to fail loudly in dev (LIQPAY uses
 * 199.00 UAH, STRIPE uses 9.99 USD) so we can smoke-test
 * without a live plan service.</p>
 */
@Service
@Slf4j
public class CheckoutService {

    private final LiqPayClient liqPayClient;
    private final StripeClient stripeClient;
    private final PaymentRepository paymentRepository;
    private final BillingProperties billingProperties;

    public CheckoutService(
        LiqPayClient liqPayClient,
        StripeClient stripeClient,
        PaymentRepository paymentRepository,
        BillingProperties billingProperties
    ) {
        this.liqPayClient = liqPayClient;
        this.stripeClient = stripeClient;
        this.paymentRepository = paymentRepository;
        this.billingProperties = billingProperties;
    }

    /**
     * Commit 4 — both providers are now wired.
     * LiqPay → 199.00 UAH, Stripe → 9.99 USD
     * (placeholder prices — service-to-service
     * resolution from subscription-service lands
     * in Commit 6 with the auto-renewal pipeline).
     */
    public CheckoutResult checkout(UUID userId, UUID planId, PaymentProvider provider) {
        if (provider == PaymentProvider.LIQPAY) {
            return checkoutLiqPay(userId, planId);
        }
        if (provider == PaymentProvider.STRIPE) {
            return checkoutStripe(userId, planId);
        }
        throw new IllegalArgumentException("Unsupported provider: " + provider);
    }

    private CheckoutResult checkoutLiqPay(UUID userId, UUID planId) {
        // 1. Mint a globally-unique order id. Format:
        // `{uuid-no-dashes}-liqpay` so the mobile app
        // receives a deterministic token back from the
        // webhook (the provider echoes our order id).
        final String orderId = UUID.randomUUID().toString().replace("-", "") + "-liqpay";

        // 2. Create the PENDING row. The plan price is
        // hardcoded for Phase 4.3 (Commit 3); Commit 4
        // resolves it from subscription-service.
        final BigDecimal amount = new BigDecimal("199.00");
        final String currency = "UAH";
        final Payment payment = Payment.builder()
            .userId(userId)
            .provider(PaymentProvider.LIQPAY)
            .providerOrderId(orderId)
            .amount(amount)
            .currency(currency)
            .status(PaymentStatus.PENDING)
            .description("Plan " + planId)
            .build();
        paymentRepository.saveAndFlush(payment);

        // 3. Build the LiqPay request — amount, currency,
        // sandbox flag (read from BillingProperties), the
        // webhook URL (so LiqPay knows where to POST the
        // callback).
        final LiqPayCheckoutRequest liqReq = new LiqPayCheckoutRequest(
            LiqPayCheckoutRequest.PROTOCOL_VERSION,
            billingProperties.liqpay().publicKey(),
            LiqPayCheckoutRequest.ACTION_PAY,
            amount,
            currency,
            "Plan " + planId,
            orderId,
            billingProperties.liqpay().sandbox() ? 1 : 0,
            // result_url — where the user lands after
            // checkout. Phase 4.3 redirects to a static
            // page; Phase 5+ wires a deep-link into
            // ui-mobile.
            "https://example.com/liqpay/return",
            // server_url — where LiqPay POSTs the webhook.
            // Compose it from the public base URL
            // (configurable in production via env var).
            "https://example.com/rest/ua.fin.api/billing/webhooks/liqpay",
            "uk"
        );
        final URI checkoutUrl = liqPayClient.createCheckout(liqReq);

        log.info(
            "Created LiqPay checkout: paymentId={}, orderId={}, url={}",
            payment.getPaymentId(), orderId, checkoutUrl
        );
        return new CheckoutResult(payment.getPaymentId(), checkoutUrl, PaymentProvider.LIQPAY);
    }

    private CheckoutResult checkoutStripe(UUID userId, UUID planId) {
        // 1. Mint a globally-unique order id. Same
        // convention as LiqPay: UUID-without-dashes
        // + provider suffix. The Stripe webhook
        // handler reads `metadata.order_id` to find
        // the matching SUBSCRIPTION_PAYMENTS row.
        final String orderId = UUID.randomUUID().toString().replace("-", "") + "-stripe";

        // 2. Create the PENDING row. Stripe uses
        // USD (or EUR in EU) for Phase 4.3; the
        // hardcoded 9.99 mirrors LiqPay's 199.00
        // UAH as a placeholder.
        final BigDecimal amount = new BigDecimal("9.99");
        final String currency = "USD";
        final Payment payment = Payment.builder()
            .userId(userId)
            .provider(PaymentProvider.STRIPE)
            .providerOrderId(orderId)
            .amount(amount)
            .currency(currency)
            .status(PaymentStatus.PENDING)
            .description("Plan " + planId)
            .build();
        paymentRepository.saveAndFlush(payment);

        // 3. Build the Stripe request. The orderId,
        // userId, planId are all embedded in the
        // Checkout Session metadata so the webhook
        // can correlate the event back to our row.
        final StripeCheckoutRequest stripeReq = new StripeCheckoutRequest(
            userId,
            planId,
            amount,
            currency,
            "Plan " + planId,
            orderId,
            "https://example.com/stripe/return",
            "https://example.com/stripe/cancel"
        );
        final URI checkoutUrl = stripeClient.createCheckout(stripeReq);

        log.info(
            "Created Stripe checkout: paymentId={}, orderId={}, url={}",
            payment.getPaymentId(), orderId, checkoutUrl
        );
        return new CheckoutResult(payment.getPaymentId(), checkoutUrl, PaymentProvider.STRIPE);
    }

    /**
     * Result of a successful checkout — what the mobile app
     * gets back. {@code paymentId} is the
     * {@code SUBSCRIPTION_PAYMENTS.PAYMENT_ID} that
     * transitions to {@code SUCCEEDED} on the webhook.
     */
    public record CheckoutResult(
        UUID paymentId,
        URI checkoutUrl,
        PaymentProvider provider
    ) {}
}
