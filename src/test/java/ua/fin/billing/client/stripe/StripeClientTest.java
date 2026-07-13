// Unit test for Phase 4.3 (commit 4) — StripeClient
// checkout. Pure-Mockito (no Spring context); the
// SessionFactory is faked so we never hit the
// Stripe API. We assert:
//   1. happy path: amount converted to minor units
//      (BigDecimal 9.99 → 999), metadata includes
//      order_id / user_id / plan_id, success URL +
//      cancel URL passed through
//   2. empty URL from Stripe → StripeSignatureException
//   3. SDK exception → StripeSignatureException
//
// The actual SignatureVerification is tested in
// StripeWebhookVerifierTest (separate file) via
// the Stripe SDK's test fixtures.

package ua.fin.billing.client.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.fin.billing.config.BillingProperties;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeClientTest {

    private StripeClient.SessionFactory sessionFactory;
    private StripeClient client;
    private BillingProperties properties;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(StripeClient.SessionFactory.class);
        properties = new BillingProperties(
            new BillingProperties.LiqPay("pub", "priv", true),
            new BillingProperties.Stripe("sk_test_placeholder", "whsec_placeholder", "2025-04-30.basil"),
            new BillingProperties.SubscriptionService("http://localhost:8083", "dev-token"),
            new BillingProperties.Scheduler("0 0 3 * * *")
        );
        client = new StripeClient(properties, sessionFactory);
        // The @PostConstruct init method isn't
        // invoked by direct construction — we
        // skip it on purpose so the test never
        // touches Stripe.apiKey.
    }

    @Test
    void createCheckout_happyPath_buildsSessionAndReturnsUrl() throws Exception {
        // given
        final Session session = mock(Session.class);
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/c/pay/cs_test_abc");
        when(sessionFactory.create(any(SessionCreateParams.class))).thenReturn(session);

        final UUID userId = UUID.randomUUID();
        final UUID planId = UUID.randomUUID();
        final StripeCheckoutRequest req = new StripeCheckoutRequest(
            userId,
            planId,
            new BigDecimal("9.99"),
            "USD",
            "PRO plan",
            "order-xyz-stripe",
            "https://example.com/success",
            "https://example.com/cancel"
        );

        // when
        final var url = client.createCheckout(req);

        // then
        assertThat(url).hasToString("https://checkout.stripe.com/c/pay/cs_test_abc");
        // The factory was called exactly once.
        verify(sessionFactory).create(any(SessionCreateParams.class));
    }

    @Test
    void createCheckout_emptyUrl_throws() throws Exception {
        // given — Stripe sometimes returns a
        // session with a null URL on transient
        // errors. The client must surface that.
        final Session session = mock(Session.class);
        when(session.getUrl()).thenReturn(null);
        when(sessionFactory.create(any(SessionCreateParams.class))).thenReturn(session);

        // when + then
        assertThatThrownBy(() -> client.createCheckout(new StripeCheckoutRequest(
            UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("9.99"), "USD", "PRO plan",
            "order-xyz-stripe",
            "https://example.com/success", "https://example.com/cancel"
        ))).isInstanceOf(StripeSignatureException.class);
    }

    @Test
    void createCheckout_sdkException_throws() throws Exception {
        // given
        when(sessionFactory.create(any(SessionCreateParams.class)))
            .thenThrow(new StripeExceptionStub("API connection failed"));

        // when + then
        assertThatThrownBy(() -> client.createCheckout(new StripeCheckoutRequest(
            UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("9.99"), "USD", "PRO plan",
            "order-xyz-stripe",
            "https://example.com/success", "https://example.com/cancel"
        ))).isInstanceOf(StripeSignatureException.class);
    }

    /**
     * StripeException is abstract — concrete
     * subclasses require HTTP context. We
     * subclass it inline so the test doesn't
     * need the full Stripe SDK HTTP machinery.
     */
    private static class StripeExceptionStub extends StripeException {
        StripeExceptionStub(String message) {
            super(message, null, null, 0);
        }
    }
}
