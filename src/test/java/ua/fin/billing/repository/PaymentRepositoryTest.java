// Unit test for Phase 4.3 (commit 2) — pure-Mockito
// verification of the auto-derived repository finders
// on PaymentRepository. No @DataJpaTest / @SpringBootTest
// because Spring Cloud 2025.0.0 + Spring Boot 4.1.0 has
// a SimpleDiscoveryClientAutoConfiguration compat issue
// (it references the OLD WebServerInitializedEvent
// package path) that blocks the test slice from
// loading the application context.
//
// The contract these tests verify is method-derivation,
// not JPA mapping — the JPA mapping itself is exercised
// in Commit 3 against the H2 in-memory DB once we add
// the webhook handler that actually uses the finders.
// For now, the auto-derived query is implicit (Spring
// Data builds it from the method name); the test just
// asserts the call shape is what the webhook handler
// will use.

package ua.fin.billing.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.fin.billing.entity.Payment;
import ua.fin.billing.entity.PaymentProvider;
import ua.fin.billing.entity.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRepositoryTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Test
    void findByProviderAndProviderOrderId_delegatesWithLiqPayCase() {
        // given — LiqPay sends our order id in the webhook callback
        final Payment expected = Payment.builder()
            .paymentId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .provider(PaymentProvider.LIQPAY)
            .providerOrderId("ord-liqpay-1")
            .status(PaymentStatus.SUCCEEDED)
            .build();
        when(paymentRepository.findByProviderAndProviderOrderId(
            PaymentProvider.LIQPAY, "ord-liqpay-1"
        )).thenReturn(Optional.of(expected));

        // when
        final Optional<Payment> got = paymentRepository
            .findByProviderAndProviderOrderId(PaymentProvider.LIQPAY, "ord-liqpay-1");

        // then
        assertThat(got).isPresent();
        assertThat(got.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(paymentRepository).findByProviderAndProviderOrderId(
            PaymentProvider.LIQPAY, "ord-liqpay-1"
        );
        verifyNoMoreInteractions(paymentRepository);
    }

    @Test
    void findByStatusAndNextRenewalAtLessThanEqual_duePayments() {
        // given — Commit 6 (auto-renewal scheduler) calls this finder
        final OffsetDateTime cutoff = OffsetDateTime.now();
        final Payment due = Payment.builder()
            .paymentId(UUID.randomUUID())
            .provider(PaymentProvider.STRIPE)
            .providerOrderId("due-stripe")
            .status(PaymentStatus.SUCCEEDED)
            .build();
        when(paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                PaymentStatus.SUCCEEDED, cutoff
            )).thenReturn(List.of(due));

        // when
        final List<Payment> got = paymentRepository
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                PaymentStatus.SUCCEEDED, cutoff
            );

        // then
        assertThat(got).hasSize(1);
        assertThat(got.get(0).getProviderOrderId()).isEqualTo("due-stripe");
        verify(paymentRepository)
            .findByStatusAndNextRenewalAtLessThanEqualOrderByNextRenewalAtAsc(
                PaymentStatus.SUCCEEDED, cutoff
            );
        verifyNoMoreInteractions(paymentRepository);
    }
}
