package ua.fin.billing.client.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 4.3 (Commit 4) — wires the
 * {@link StripeClient.SessionFactory} used by
 * {@link StripeClient#createCheckout(StripeCheckoutRequest)}.
 *
 * <p>Production wiring is the method reference
 * {@code Session::create}. Tests override this
 * bean with a fake factory that returns a
 * pre-built {@link Session}.</p>
 *
 * <p>Note: {@code Session.create} throws the
 * checked {@link StripeException}. The
 * {@link StripeClient.SessionFactory} interface
 * declares it, so the lambda propagates it
 * naturally — no need for a sneaky-throw.</p>
 */
@Configuration
public class StripeConfig {

    @Bean
    public StripeClient.SessionFactory stripeSessionFactory() {
        return SessionCreateParams -> Session.create(SessionCreateParams);
    }
}
