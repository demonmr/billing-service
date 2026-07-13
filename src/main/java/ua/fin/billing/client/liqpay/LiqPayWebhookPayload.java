package ua.fin.billing.client.liqpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase 4.3 — LiqPay webhook payload (decoded from the
 * base64 {@code data} field of the form-encoded POST).
 *
 * <p>Field names match LiqPay's wire format exactly. The
 * {@code status} values are documented at
 * <a href="https://www.liqpay.ua/documentation/en/api/return_url">
 * liqpay.ua/documentation/en/api/return_url</a> — we only
 * care about {@code success}, {@code failure}, and
 * {@code error}; the others (e.g. {@code subscribed},
 * {@code unsubscribed}) are subscription-lifecycle events
 * we don't use in Phase 4.3.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiqPayWebhookPayload(
    @JsonProperty("version") Integer version,
    @JsonProperty("public_key") String publicKey,
    @JsonProperty("action") String action,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("payment_id") Long paymentId,
    @JsonProperty("status") String status,
    @JsonProperty("amount") java.math.BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("description") String description,
    @JsonProperty("err_code") String errCode,
    @JsonProperty("err_description") String errDescription,
    @JsonProperty("info") String info
) {
    /** LiqPay reports a successful charge. */
    public static final String STATUS_SUCCESS = "success";
    /** LiqPay reports a failed charge (declined card, etc.). */
    public static final String STATUS_FAILURE = "failure";
    /** LiqPay reports a payment error. */
    public static final String STATUS_ERROR = "error";

    public boolean isSuccess() {
        return STATUS_SUCCESS.equalsIgnoreCase(status);
    }

    public boolean isFailure() {
        return STATUS_FAILURE.equalsIgnoreCase(status) || STATUS_ERROR.equalsIgnoreCase(status);
    }
}
