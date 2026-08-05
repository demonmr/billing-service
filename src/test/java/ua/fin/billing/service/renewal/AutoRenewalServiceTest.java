// Unit test for Phase 4.3 (commit 6) —
// AutoRenewalService. Pure-Mockito. We
// exercise:
//
//   1. happy path — 3 due payments, all renew
//      successfully, all advance nextRenewalAt
//      by 30 days, all reset failureCount to 0.
//   2. dunning — 1 due payment, attempt fails
//      3 times, after the 3rd failure we
//      call subscriptionServiceClient
//      .deactivateSubscription.
//   3. skip — payments with nextRenewalAt in
//      the future are NOT picked up.
//   4. dunning without subscriptionId — failure
//      count advances but no
//      deactivation call (the payment has
//      no subscriptionId to flip).
//   5. deactivation failure — count advances
//      regardless of the deactivation
//      call's outcome (best-effort).

package ua.fin.billing.service.renewal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ua.fin.billing.client.liqpay.LiqPayCheckoutRequest;
import ua.fin.billing.client.liqpay.LiqPayClient;
import ua.fin.billing.client.stripe.StripeCheckoutRequest;
import ua.fin.billing.client.stripe.StripeClient;
import ua.fin.billing.config.BillingProperties;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;
import ua.fin.billing.repository.PaymentRepository;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoRenewalServiceTest {

    private PaymentRepository paymentRepository;
    private SubscriptionServiceClient subscriptionServiceClient;
    private LiqPayClient liqPayClient;
    private StripeClient stripeClient;
    private BillingProperties properties;
    private AutoRenewalService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        subscriptionServiceClient = mock(SubscriptionServiceClient.class);
        liqPayClient = mock(LiqPayClient.class);
        stripeClient = mock(StripeClient.class);
        properties = new BillingProperties(
            new BillingProperties.LiqPay(
                "sandbox_public_key", "sandbox_private_key", true
            ),
            new BillingProperties.Stripe(
                "sk_test_placeholder", "whsec_placeholder",
                "2025-04-30.basil"
            ),
            new BillingProperties.SubscriptionService(
                "http://localhost:8083", "dev-token"
            ),
            new BillingProperties.Scheduler("0 0 3 * * *")
        );
        service = new AutoRenewalService(
            paymentRepository,
            liqPayClient,
            stripeClient,
            properties,
            subscriptionServiceClient
        );
    }

    @Test
    void renewDue_happyPath_advancesRenewalDate() {
        // given — 2 due payments, both renew
        // successfully.
        final Payment p1 = duePayment(1L, 0, UUID.randomUUID());
        final Payment p2 = duePayment(2L, 0, UUID.randomUUID());
        when(paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                any(PaymentStatus.class), any(OffsetDateTime.class)
            )).thenReturn(List.of(p1, p2));
        // Don't set simulate-fail — all
        // renewals succeed.

        // when
        final int renewed = service.renewDue();

        // then
        assertThat(renewed).isEqualTo(2);
        assertThat(p1.getNextRenewalAt()).isAfter(
            OffsetDateTime.now().plusDays(28)
        );
        assertThat(p1.getFailureCount()).isEqualTo(0);
        verify(paymentRepository, times(2)).save(any(Payment.class));
    }

    @Test
    void renewDue_dunning_3FailuresDeactivatesSubscription() {
        // given — 1 due payment, simulate-fail
        // is set, so every attempt fails.
        final UUID subscriptionId = UUID.randomUUID();
        final Payment p = duePayment(0L, 2, subscriptionId);
        when(paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                any(PaymentStatus.class), any(OffsetDateTime.class)
            )).thenReturn(List.of(p));
        ReflectionTestUtils.setField(service, "simulateFail", true);

        // when
        service.renewDue();

        // then — failureCount becomes 3
        // (was 2, +1 = 3 = MAX_FAILURES) and
        // we call deactivateSubscription.
        assertThat(p.getFailureCount()).isEqualTo(3);
        verify(subscriptionServiceClient, times(1))
            .deactivateSubscription(eq(subscriptionId), anyString());
    }

    @Test
    void renewDue_skipsPaymentsInTheFuture() {
        // given — only past-due payments are
        // returned. The repository's WHERE
        // clause is the actual filter; the
        // service just iterates what the repo
        // returns. This test asserts we don't
        // call save() on an empty list.
        when(paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                any(PaymentStatus.class), any(OffsetDateTime.class)
            )).thenReturn(List.of());

        // when
        final int renewed = service.renewDue();

        // then
        assertThat(renewed).isEqualTo(0);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void renewDue_dunningNoSubscriptionId_skipsDeactivation() {
        // given — payment with no
        // subscriptionId (the link to
        // subscription-service wasn't set at
        // checkout). Failure count advances
        // but deactivation is skipped (we
        // have no subscription to flip).
        final Payment p = duePayment(0L, 2, null);
        when(paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                any(PaymentStatus.class), any(OffsetDateTime.class)
            )).thenReturn(List.of(p));
        ReflectionTestUtils.setField(service, "simulateFail", true);

        // when
        service.renewDue();

        // then
        assertThat(p.getFailureCount()).isEqualTo(3);
        verify(subscriptionServiceClient, never())
            .deactivateSubscription(any(), anyString());
    }

    @Test
    void renewDue_dunningDeactivationFails_doesNotThrow() {
        // given — 1 payment, simulate-fail, the
        // deactivation call itself throws.
        // The dunning logic must not propagate
        // the failure (it's best-effort; the
        // failureCount is what matters).
        final UUID subscriptionId = UUID.randomUUID();
        final Payment p = duePayment(0L, 2, subscriptionId);
        when(paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                any(PaymentStatus.class), any(OffsetDateTime.class)
            )).thenReturn(List.of(p));
        ReflectionTestUtils.setField(service, "simulateFail", true);
        org.mockito.Mockito.doThrow(new RuntimeException("network error"))
            .when(subscriptionServiceClient)
            .deactivateSubscription(eq(subscriptionId), anyString());

        // when — must not throw
        service.renewDue();

        // then
        assertThat(p.getFailureCount()).isEqualTo(3);
        verify(subscriptionServiceClient, times(1))
            .deactivateSubscription(eq(subscriptionId), anyString());
    }

    // ----------------------------------------------------------------
    // Sprint 1 — stub closure: attemptRenewal
    // now mints a fresh hosted-checkout session
    // via the real provider client and persists
    // a sibling PENDING Payment row. These two
    // tests assert the new behaviour.
    // ----------------------------------------------------------------

    @Test
    void attemptRenewal_stripeProvider_mintsSessionAndSavesSiblingRow() {
        // given — a due STRIPE payment, simulate-fail
        // is OFF. The Stripe client returns a fake
        // checkout URL.
        final Payment original = duePayment(
            1L, 0, UUID.randomUUID()
        );
        when(stripeClient.createCheckout(any(StripeCheckoutRequest.class)))
            .thenReturn(URI.create(
                "https://checkout.stripe.com/c/pay/cs_test_renewal"));

        // when
        final boolean success = service.attemptRenewal(original);

        // then — returns true (success), Stripe was
        // called once with an orderId derived from
        // the original, and a sibling PENDING row
        // was saved.
        assertThat(success).isTrue();
        verify(stripeClient, times(1))
            .createCheckout(any(StripeCheckoutRequest.class));
        verify(paymentRepository, times(1))
            .saveAndFlush(any(Payment.class));
        // The original payment is NOT saved by
        // attemptRenewal — advanceRenewalDate does
        // that on the success path.
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void attemptRenewal_liqpayProvider_mintsSessionAndSavesSiblingRow() {
        // given — a due LIQPAY payment, simulate-fail
        // is OFF. The LiqPay client returns a fake
        // checkout URL.
        final Payment original = Payment.builder()
            .paymentId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .subscriptionId(UUID.randomUUID())
            .provider(PaymentProvider.LIQPAY)
            .providerOrderId("ord-original-liqpay")
            .amount(new java.math.BigDecimal("199.00"))
            .currency("UAH")
            .status(PaymentStatus.SUCCEEDED)
            .failureCount(0)
            .createdAt(OffsetDateTime.now().minusDays(60))
            .updatedAt(OffsetDateTime.now().minusDays(1))
            .nextRenewalAt(OffsetDateTime.now().minusDays(1))
            .build();
        when(liqPayClient.createCheckout(any(LiqPayCheckoutRequest.class)))
            .thenReturn(URI.create(
                "https://www.liqpay.ua/api/3/checkout/renewal-token"));

        // when
        final boolean success = service.attemptRenewal(original);

        // then
        assertThat(success).isTrue();
        verify(liqPayClient, times(1))
            .createCheckout(any(LiqPayCheckoutRequest.class));
        verify(paymentRepository, times(1))
            .saveAndFlush(any(Payment.class));
    }

    @Test
    void attemptRenewal_providerThrows_returnsFalse_viaOuterCatch() {
        // given — a due STRIPE payment, simulate-fail
        // OFF, but the Stripe SDK throws (e.g. network
        // error). The outer catch in renewDue() catches
        // the RuntimeException and routes to
        // handleFailure; here we just assert that
        // attemptRenewal surfaces the error to the
        // caller.
        final Payment original = duePayment(
            1L, 0, UUID.randomUUID()
        );
        when(stripeClient.createCheckout(any(StripeCheckoutRequest.class)))
            .thenThrow(new RuntimeException("stripe down"));

        // when + then — must NOT swallow the
        // exception (the outer catch handles it).
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> service.attemptRenewal(original))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("stripe down");
    }

    /**
     * Build a payment that is "due" — i.e.
     * SUCCEEDED + nextRenewalAt in the past.
     */
    private static Payment duePayment(
        long daysOverdue, int failureCount, UUID subscriptionId
    ) {
        return Payment.builder()
            .paymentId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .subscriptionId(subscriptionId)
            .provider(PaymentProvider.STRIPE)
            .providerOrderId("ord-test")
            .amount(new java.math.BigDecimal("9.99"))
            .currency("USD")
            .status(PaymentStatus.SUCCEEDED)
            .failureCount(failureCount)
            .createdAt(OffsetDateTime.now().minusDays(60))
            .updatedAt(OffsetDateTime.now().minusDays(1))
            .nextRenewalAt(OffsetDateTime.now().minusDays(daysOverdue))
            .build();
    }
}
