package ua.fin.billing.client.liqpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 4.3 — LiqPay hosted-checkout response.
 *
 * <p>The LiqPay server returns the redirect URL the mobile
 * app should open in its WebView. On sandbox the URL has
 * the {@code https://www.liqpay.ua/api/3/checkout/...}
 * prefix; on prod the same shape but with the merchant's
 * prod keys in the signed query params.</p>
 *
 * <p>The response is also base64-encoded JSON wrapped in a
 * {@code data} field with a sibling {@code signature} — we
 * re-verify the signature on the response to defeat MITM
 * (a hostile proxy can't forge a valid signature without
 * the merchant's private key).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiqPayCheckoutResponse(
    @JsonProperty("url") String url,
    @JsonProperty("token") String token
) {}
