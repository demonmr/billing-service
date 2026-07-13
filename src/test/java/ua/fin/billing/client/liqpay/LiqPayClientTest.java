// Unit test for Phase 4.3 (commit 3) — LiqPayClient
// outbound + inbound. Pure-Mockito + manual base64
// (no Spring context, no MockRestServiceServer — the
// RestClient builder is wired manually so the test
// has full control over the mock transport).
//
// What's covered:
//
//   1. createCheckout — sends a base64 + signature
//      form body, decodes the {url, token} response
//      and returns the URL as a URI. The signature
//      in the form body must verify against the
//      data with the same private key.
//   2. createCheckout — empty response (LiqPay
//      returns {} on error) → LiqPaySignatureException
//      is thrown.
//   3. decodeAndVerifyWebhook — happy path: a
//      signed base64 + signature decodes to the
//      payload object.
//   4. decodeAndVerifyWebhook — bad signature →
//      throws LiqPaySignatureException.
//   5. decodeAndVerifyWebhook — bad base64 →
//      throws LiqPaySignatureException.

package ua.fin.billing.client.liqpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ua.fin.billing.config.BillingProperties;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiqPayClientTest {

    private MockWebServer mockWebServer;
    private LiqPayClient client;
    private LiqPaySignatureService signatureService;
    private ObjectMapper objectMapper;
    private BillingProperties properties;
    private static final String PRIVATE_KEY = "sandbox_private_key_xyz";
    private static final String PUBLIC_KEY = "sandbox_public_key_abc";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        objectMapper = new ObjectMapper();
        signatureService = new LiqPaySignatureService();
        properties = new BillingProperties(
            new BillingProperties.LiqPay(PUBLIC_KEY, PRIVATE_KEY, true),
            new BillingProperties.Stripe("sk_test_placeholder", "whsec_placeholder", "2025-04-30.basil"),
            new BillingProperties.SubscriptionService("http://localhost:8083", "dev-token"),
            new BillingProperties.Scheduler("0 0 3 * * *")
        );

        // Build a RestClient pointed at the mock
        // server so we never hit the real LiqPay
        // endpoint in tests. The production
        // constructor hardcodes the baseUrl to
        // LIQPAY_CHECKOUT_URL (we only ever call one
        // endpoint), so for tests we use the
        // package-private constructor that takes an
        // already-built RestClient and trusts the
        // caller's baseUrl.
        final RestClient restClient = RestClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .build();
        client = new LiqPayClient(restClient, objectMapper, signatureService, properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void createCheckout_signsAndDecodesResponse() throws Exception {
        // given — the mock LiqPay server returns a
        // valid {url, token} response.
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"url\":\"https://www.liqpay.ua/api/3/checkout/abc\",\"token\":\"tok_xyz\"}"));

        // when
        final var checkoutUrl = client.createCheckout(new LiqPayCheckoutRequest(
            3, PUBLIC_KEY, "pay", new BigDecimal("199.00"),
            "UAH", "PRO plan", "abc123-liqpay", 1, null, null, "uk"
        ));

        // then — the URL matches what the mock returned
        assertThat(checkoutUrl).hasToString("https://www.liqpay.ua/api/3/checkout/abc");

        // and the form body that hit the wire had a
        // verifiable signature
        final var recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        final String body = recorded.getBody().readUtf8();
        // body is form-encoded: data=...&signature=...
        assertThat(body).contains("data=");
        assertThat(body).contains("signature=");
        // extract the data + signature and verify
        final String dataValue = extractFormField(body, "data");
        final String sigValue = extractFormField(body, "signature");
        assertThat(signatureService.verify(PRIVATE_KEY, dataValue, sigValue))
            .as("the signature in the form body must verify with the configured private key")
            .isTrue();
    }

    @Test
    void createCheckout_emptyResponse_throws() {
        mockWebServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{}"));

        assertThatThrownBy(() -> client.createCheckout(new LiqPayCheckoutRequest(
            3, PUBLIC_KEY, "pay", new BigDecimal("199.00"),
            "UAH", "PRO plan", "abc123-liqpay", 1, null, null, "uk"
        ))).isInstanceOf(LiqPaySignatureException.class);
    }

    @Test
    void decodeAndVerifyWebhook_happyPath() {
        // given
        final String json = """
            {"version":3,"public_key":"%s","action":"pay",
             "order_id":"ord-1","payment_id":42,"status":"success",
             "amount":"199.00","currency":"UAH"}
            """.formatted(PUBLIC_KEY);
        final String base64Data = Base64.getEncoder().encodeToString(
            json.getBytes(StandardCharsets.UTF_8)
        );
        final String signature = signatureService.sign(PRIVATE_KEY, base64Data);

        // when
        final LiqPayWebhookPayload payload = client.decodeAndVerifyWebhook(
            base64Data, signature
        );

        // then
        assertThat(payload.orderId()).isEqualTo("ord-1");
        assertThat(payload.status()).isEqualTo("success");
        assertThat(payload.paymentId()).isEqualTo(42L);
        assertThat(payload.isSuccess()).isTrue();
    }

    @Test
    void decodeAndVerifyWebhook_badSignature_throws() {
        // given
        final String json = "{\"order_id\":\"ord-1\",\"status\":\"success\"}";
        final String base64Data = Base64.getEncoder().encodeToString(
            json.getBytes(StandardCharsets.UTF_8)
        );
        final String wrongSignature = "AAAA" + Base64.getEncoder().encodeToString(
            new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
        );

        // when + then
        assertThatThrownBy(() ->
            client.decodeAndVerifyWebhook(base64Data, wrongSignature)
        ).isInstanceOf(LiqPaySignatureException.class);
    }

    @Test
    void decodeAndVerifyWebhook_badBase64_throws() {
        // given
        final String base64Data = "!!!not-base64!!!";
        final String signature = signatureService.sign(PRIVATE_KEY, "ignored");

        // when + then
        assertThatThrownBy(() ->
            client.decodeAndVerifyWebhook(base64Data, signature)
        ).isInstanceOf(LiqPaySignatureException.class);
    }

    /**
     * Extract a single form-URL-encoded field from a
     * form body like {@code key1=v1&key2=v2}. We use
     * this instead of pulling in a URL-decoding util
     * — values are base64 alphabet only so we don't
     * need full URL-decode for the assertion path.
     */
    private static String extractFormField(String body, String key) {
        final String prefix = key + "=";
        final int start = body.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        final int valueStart = start + prefix.length();
        final int end = body.indexOf('&', valueStart);
        final String raw = end < 0
            ? body.substring(valueStart)
            : body.substring(valueStart, end);
        // The test asserts against the raw base64 (no
        // +/→space replacement) so we URL-decode just
        // the +/= chars that base64 alphabet uses.
        return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
