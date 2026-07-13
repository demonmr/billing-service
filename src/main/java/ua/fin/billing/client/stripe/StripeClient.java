package ua.fin.billing.client.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.ProductData;
import com.stripe.param.checkout.SessionCreateParams.PaymentMethodType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ua.fin.billing.config.BillingProperties;

import java.math.BigDecimal;
import java.net.URI;

/**
 * Phase 4.3 (Commit 4) — thin wrapper around
 * Stripe's hosted-checkout (Checkout Session) API.
 *
 * <p>Stripe's API surface is a lot larger than
 * LiqPay's (Subscriptions, Customers, Refunds,
 * Connect, ...), so we deliberately keep this
 * client to the single operation we need for
 * Phase 4.3: mint a Checkout Session URL for a
 * one-time payment. Subscription mode +
 * {@code StripeCustomer} are Phase 5+ concerns.</p>
 *
 * <p>Wire format: a single HTTPS call to
 * {@code https://api.stripe.com/v1/checkout/sessions}.
 * The SDK handles auth (Bearer of the API key,
 * pinned once on startup), JSON marshalling, and
 * retry. We only build the
 * {@link SessionCreateParams} and read the
 * resulting {@code session.url}.</p>
 *
 * <p>For tests, the constructor takes a
 * {@code SessionFactory} interface so a fake
 * factory can return pre-built sessions without
 * hitting the network. The production wiring
 * uses the real {@code Session::create} method
 * reference.</p>
 */
@Component
@Slf4j
public class StripeClient {

    /** Stripe's API version. Pinned in
     * {@link BillingProperties.Stripe#apiVersion()}
     * so we can upgrade deliberately. */
    private final BillingProperties.Stripe stripeProperties;
    private final SessionFactory sessionFactory;

    public StripeClient(
        BillingProperties billingProperties,
        SessionFactory sessionFactory
    ) {
        this.stripeProperties = billingProperties.stripe();
        this.sessionFactory = sessionFactory;
    }

    /**
     * Production wiring — uses the real Stripe
     * SDK. Called by Spring after construction.
     */
    @PostConstruct
    void initStripeApiKey() {
        // The SDK reads the static key on every
        // request. Setting it once at startup is
        // safe because BillingProperties is
        // immutable. The test-only constructor
        // skips this — tests don't need a real
        // API key.
        Stripe.apiKey = stripeProperties.apiKey();
        log.info("Stripe API key configured (apiVersion={})",
            stripeProperties.apiVersion());
    }

    /**
     * Mint a hosted-checkout URL for a
     * {@code PAYMENT}-mode Checkout Session. The
     * mobile app should open the URL in a
     * WebView. The user pays, Stripe redirects
     * to {@code successUrl}, and Stripe POSTs the
     * result to
     * {@code /rest/ua.fin.api/billing/webhooks/stripe}.
     */
    public URI createCheckout(StripeCheckoutRequest request) {
        // Convert our BigDecimal amount to the
        // smallest currency unit Stripe expects
        // (cents for USD/EUR). LiqPay takes
        // decimal UAH, Stripe takes integer minor
        // units — different wire formats.
        final long amountInMinorUnits = request.amount()
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValueExact();

        final SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(request.successUrl())
            .setCancelUrl(request.cancelUrl())
            .addPaymentMethodType(PaymentMethodType.CARD)
            // Embed our orderId in metadata so
            // the webhook handler can correlate
            // the Stripe event back to our
            // SUBSCRIPTION_PAYMENTS row.
            .putMetadata("order_id", request.orderId())
            .putMetadata("user_id", request.userId().toString())
            .putMetadata("plan_id", request.planId().toString())
            .addLineItem(
                LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(
                        PriceData.builder()
                            .setCurrency(request.currency().toLowerCase())
                            .setUnitAmount(amountInMinorUnits)
                            .setProductData(
                                ProductData.builder()
                                    .setName(request.description())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

        log.debug(
            "POST Stripe checkout (orderId={}, amount={} {})",
            request.orderId(), request.amount(), request.currency()
        );
        try {
            final Session session = sessionFactory.create(params);
            final String url = session.getUrl();
            if (url == null || url.isBlank()) {
                throw new StripeSignatureException(
                    "Stripe returned a checkout session with no URL"
                );
            }
            return URI.create(url);
        } catch (StripeException e) {
            throw new StripeSignatureException(
                "Stripe checkout session creation failed: "
                    + e.getMessage(), e
            );
        }
    }

    /**
     * Factory for {@link Session} creation. Lets
     * the test inject a fake that returns a
     * pre-built session; production wires
     * {@code Session::create}.
     */
    @FunctionalInterface
    public interface SessionFactory {
        Session create(SessionCreateParams params) throws StripeException;
    }
}
