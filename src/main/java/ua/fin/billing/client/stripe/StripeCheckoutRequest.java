package ua.fin.billing.client.stripe;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Phase 4.3 (Commit 4) — internal DTO for
 * {@link StripeClient#createCheckout(StripeCheckoutRequest)}.
 *
 * <p>Note: this is NOT a wire DTO. The wire
 * format is Stripe's {@code SessionCreateParams}
 * (built inline in {@link StripeClient}). This
 * record is what our own {@code CheckoutService}
 * hands the client — it has the userId, planId,
 * orderId, amount, currency we already minted in
 * the {@code SUBSCRIPTION_PAYMENTS} row.</p>
 */
public record StripeCheckoutRequest(
    UUID userId,
    UUID planId,
    BigDecimal amount,
    String currency,
    String description,
    String orderId,
    String successUrl,
    String cancelUrl
) {}
