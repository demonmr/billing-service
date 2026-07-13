// Unit test for Phase 4.3 (commit 4) — StripeWebhookVerifier.
// Pure-Mockito + manual HMAC-SHA256 (we don't need
// a Spring context, the SDK does the heavy lifting).
//
// What's covered:
//
//   1. happy path — we sign a payload manually
//      (HMAC-SHA256 of "{t}.{body}" with the secret
//      as key, hex-encoded), build a
//      "t=...,v1=..." Stripe-Signature header,
//      and assert verifyAndParse() returns the
//      deserialized Event.
//   2. bad signature — we flip a single hex char
//      in the v1 value. SDK throws
//      SignatureVerificationException →
//      StripeSignatureException.
//   3. missing signature header →
//      StripeSignatureException.
//   4. empty body → StripeSignatureException.
//   5. timestamp far in the past (>5min tolerance) →
//      StripeSignatureException (replay protection).
//
// We intentionally do NOT use Stripe's test
// secret / test events — the SDK doesn't expose
// them in a way that doesn't require network
// access. Hand-rolling the signature is the
// pattern Stripe's docs recommend for unit
// tests (see
// https://docs.stripe.com/webhooks/signature
// § "Manually verifying signatures").

package ua.fin.billing.client.stripe;

import com.stripe.model.Event;
import org.junit.jupiter.api.Test;
import ua.fin.billing.config.BillingProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeWebhookVerifierTest {

    private static final String WEBHOOK_SECRET = "whsec_test_super_secret";

    private final StripeWebhookVerifier verifier = new StripeWebhookVerifier(
        new BillingProperties(
            new BillingProperties.LiqPay("pub", "priv", true),
            new BillingProperties.Stripe(
                "sk_test_placeholder", WEBHOOK_SECRET, "2025-04-30.basil"
            ),
            new BillingProperties.SubscriptionService("http://localhost:8083", "dev-token"),
            new BillingProperties.Scheduler("0 0 3 * * *")
        )
    );

    @Test
    void verifyAndParse_happyPath_returnsTypedEvent() {
        // given — a minimal checkout.session.completed
        // payload, signed manually with the same
        // secret the verifier uses.
        final String body = """
            {
              "id": "evt_test_123",
              "type": "checkout.session.completed",
              "data": {"object": {"id": "cs_test_abc", "metadata": {}}}
            }
            """;
        final long timestamp = System.currentTimeMillis() / 1000L;
        final String signatureHeader = buildHeader(body, timestamp, WEBHOOK_SECRET);

        // when
        final Event event = verifier.verifyAndParse(body, signatureHeader);

        // then
        assertThat(event.getId()).isEqualTo("evt_test_123");
        assertThat(event.getType()).isEqualTo("checkout.session.completed");
    }

    @Test
    void verifyAndParse_badSignature_throws() {
        // given
        final String body = "{\"id\":\"evt_test_456\",\"type\":\"checkout.session.completed\"}";
        final long timestamp = System.currentTimeMillis() / 1000L;
        // Use a wrong secret to produce a v1 that
        // won't match the configured secret.
        final String badHeader = buildHeader(body, timestamp, "wrong_secret");

        // when + then
        assertThatThrownBy(() ->
            verifier.verifyAndParse(body, badHeader)
        ).isInstanceOf(StripeSignatureException.class);
    }

    @Test
    void verifyAndParse_missingHeader_throws() {
        final String body = "{\"id\":\"evt_test_789\",\"type\":\"checkout.session.completed\"}";

        assertThatThrownBy(() ->
            verifier.verifyAndParse(body, null)
        ).isInstanceOf(StripeSignatureException.class);

        assertThatThrownBy(() ->
            verifier.verifyAndParse(body, "")
        ).isInstanceOf(StripeSignatureException.class);
    }

    @Test
    void verifyAndParse_emptyBody_throws() {
        // The signature header is non-empty but
        // the body is blank — the verifier should
        // reject this BEFORE invoking the SDK
        // (otherwise the SDK's parse would throw
        // a less informative exception).
        assertThatThrownBy(() ->
            verifier.verifyAndParse("", "t=1,v1=deadbeef")
        ).isInstanceOf(StripeSignatureException.class);

        assertThatThrownBy(() ->
            verifier.verifyAndParse(null, "t=1,v1=deadbeef")
        ).isInstanceOf(StripeSignatureException.class);
    }

    @Test
    void verifyAndParse_expiredTimestamp_throws() {
        // given — sign with a timestamp 1 hour
        // ago, well outside the SDK's default
        // 5-minute tolerance window.
        final String body = "{\"id\":\"evt_old\",\"type\":\"checkout.session.completed\"}";
        final long oldTimestamp = (System.currentTimeMillis() / 1000L) - 3600L;
        final String header = buildHeader(body, oldTimestamp, WEBHOOK_SECRET);

        // when + then — the SDK rejects replay
        // attacks via the timestamp tolerance.
        assertThatThrownBy(() ->
            verifier.verifyAndParse(body, header)
        ).isInstanceOf(StripeSignatureException.class);
    }

    /**
     * Build a {@code Stripe-Signature} header value
     * of the form {@code t=<ts>,v1=<hex-hmac>}. The
     * HMAC-SHA256 is computed over
     * {@code ts + "." + body} with the secret as
     * the key. Mirrors the algorithm Stripe
     * documents at
     * https://docs.stripe.com/webhooks/signature
     * § "Manually verifying signatures" step 2 + 3.
     */
    private static String buildHeader(
        String body, long timestamp, String secret
    ) {
        try {
            final String signedPayload = timestamp + "." + body;
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            ));
            final byte[] rawHmac = mac.doFinal(
                signedPayload.getBytes(StandardCharsets.UTF_8)
            );
            final String hexHmac = toHex(rawHmac);
            return "t=" + timestamp + ",v1=" + hexHmac;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build Stripe signature", e);
        }
    }

    private static String toHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
