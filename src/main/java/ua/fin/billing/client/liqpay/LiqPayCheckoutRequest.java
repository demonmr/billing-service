package ua.fin.billing.client.liqpay;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Phase 4.3 — LiqPay checkout request payload.
 *
 * <p>The fields map 1:1 to the {@code data} JSON object that
 * LiqPay's hosted checkout widget expects. The fields are sent
 * to LiqPay as base64(json) — we never POST the JSON directly.</p>
 *
 * <p>Field names match the LiqPay wire format exactly (snake_case).
 * Do NOT use camelCase or rename {@code public_key} →
 * {@code publicKey} — LiqPay's parser is case-sensitive.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiqPayCheckoutRequest(
    @JsonProperty("version") Integer version,
    @JsonProperty("public_key") String publicKey,
    @JsonProperty("action") String action,
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("description") String description,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("sandbox") Integer sandbox,
    @JsonProperty("result_url") String resultUrl,
    @JsonProperty("server_url") String serverUrl,
    @JsonProperty("language") String language
) {
    /**
     * The only LiqPay {@code action} value we use — hosted
     * checkout (the user is redirected to LiqPay's page,
     * pays, and LiqPay POSTs the result to our webhook).
     */
    public static final String ACTION_PAY = "pay";

    /**
     * LiqPay protocol version — pinned so the JSON shape we
     * send is what their parser expects. Bump only after
     * reading the LiqPay changelog.
     */
    public static final int PROTOCOL_VERSION = 3;
}
