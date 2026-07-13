package ua.fin.billing.client.liqpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ua.fin.billing.config.BillingProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Phase 4.3 — thin RestClient wrapper around LiqPay's
 * hosted-checkout API.
 *
 * <p>The LiqPay API is a single endpoint for both prod and
 * sandbox: {@code https://www.liqpay.ua/api/3/checkout}.
 * The prod-vs-sandbox toggle is the {@code sandbox} field
 * in the JSON payload, NOT a different URL. We always POST
 * to the same URL; the {@code sandbox} flag is read from
 * {@link BillingProperties.LiqPay#sandbox()} and forwarded
 * on every checkout request.</p>
 *
 * <p>Wire format: form-encoded {@code data} (base64 of
 * JSON) + {@code signature} (base64 of SHA-1 of
 * {@code privateKey + data + privateKey}). The base64
 * encoding is mandatory — sending raw JSON in {@code data}
 * will result in a {@code 400 Bad signature} from LiqPay.</p>
 */
@Component
@Slf4j
public class LiqPayClient {

    /** LiqPay's single hosted-checkout endpoint (prod + sandbox). */
    public static final String LIQPAY_CHECKOUT_URL =
        "https://www.liqpay.ua/api/3/checkout";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LiqPaySignatureService signatureService;
    private final BillingProperties.LiqPay liqPayProperties;

    public LiqPayClient(
        RestClient.Builder builder,
        ObjectMapper objectMapper,
        LiqPaySignatureService signatureService,
        BillingProperties properties
    ) {
        // Always build with the LiqPay prod endpoint as
        // the baseUrl. The {@code RestClient.Builder} the
        // caller passes may have set other defaults
        // (interceptors, messageConverters) but the URL
        // is hardcoded to LiqPay — there's only one
        // endpoint, and rewriting it for tests would
        // require either env-var indirection or a
        // dedicated test profile. The
        // {@code LiqPayClientTest} uses a separate
        // constructor that takes a fully-built
        // {@code RestClient} (pointed at a
        // MockWebServer) instead of this one.
        this.restClient = builder
            .baseUrl(LIQPAY_CHECKOUT_URL)
            .build();
        this.objectMapper = objectMapper;
        this.signatureService = signatureService;
        this.liqPayProperties = properties.liqpay();
    }

    /**
     * Test-only constructor — takes an already-built
     * {@code RestClient} so the caller can point at a
     * MockWebServer. Production code uses the
     * builder-based constructor above.
     */
    LiqPayClient(
        RestClient restClient,
        ObjectMapper objectMapper,
        LiqPaySignatureService signatureService,
        BillingProperties properties
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.signatureService = signatureService;
        this.liqPayProperties = properties.liqpay();
    }

    /**
     * Mint a hosted-checkout URL for a {@code pay} action.
     * Returns the URL the mobile app should open in its
     * WebView.
     */
    public URI createCheckout(LiqPayCheckoutRequest request) {
        // Serialize the request JSON, then base64 it. This is
        // the value that goes in the form-encoded `data` field
        // AND the value the signature is computed over.
        final String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (java.io.IOException e) {
            // Should never happen for our own DTOs.
            throw new IllegalStateException("Failed to serialize LiqPay request", e);
        }
        final String base64Data = Base64.getEncoder()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        final String signature = signatureService.sign(
            liqPayProperties.privateKey(), base64Data
        );

        // The "response" from the checkout endpoint is the
        // JSON object {url, token} — the mobile app opens the
        // `url` field in a WebView.
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", base64Data);
        form.add("signature", signature);

        log.debug(
            "POST {} (sandbox={}, orderId={})",
            LIQPAY_CHECKOUT_URL, request.sandbox(), request.orderId()
        );
        // POST against the baseUrl configured on the
        // RestClient. Production: builder constructor
        // sets it to LIQPAY_CHECKOUT_URL. Tests: the
        // test-only constructor takes a pre-built
        // RestClient pointed at a MockWebServer, so
        // the request goes there instead. We do NOT
        // pass `.uri(LIQPAY_CHECKOUT_URL)` explicitly
        // — that would make every test hit the real
        // LiqPay endpoint regardless of the
        // configured baseUrl.
        final LiqPayCheckoutResponse response = restClient.post()
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(LiqPayCheckoutResponse.class);
        if (response == null || response.url() == null) {
            throw new LiqPaySignatureException(
                "LiqPay returned an empty checkout response"
            );
        }
        return URI.create(response.url());
    }

    /**
     * Decode + verify a LiqPay webhook's {@code data} +
     * {@code signature} pair. Returns the typed payload on
     * success, throws on signature mismatch / decode error.
     */
    public LiqPayWebhookPayload decodeAndVerifyWebhook(
        String base64Data, String base64Signature
    ) {
        if (!signatureService.verify(
            liqPayProperties.privateKey(), base64Data, base64Signature
        )) {
            throw new LiqPaySignatureException(
                "LiqPay webhook signature verification failed"
            );
        }
        final byte[] json = Base64.getDecoder().decode(base64Data);
        try {
            return objectMapper.readValue(json, LiqPayWebhookPayload.class);
        } catch (java.io.IOException e) {
            throw new LiqPaySignatureException(
                "LiqPay webhook payload JSON decode failed", e
            );
        }
    }
}
