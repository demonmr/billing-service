package ua.fin.billing;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 4.3 — billing-service entry point.
 *
 * <p>The service handles all real-money payment flows: LiqPay
 * (UA primary) + Stripe (EU/US) checkout, webhook
 * signature verification, PDF invoice generation, and a
 * daily auto-renewal scheduler. It is the source of truth
 * for the {@code SUBSCRIPTION_PAYMENTS} / {@code BILLING_INVOICES}
 * / {@code BILLING_WEBHOOK_EVENTS} tables (V23 migration).</p>
 *
 * <p>{@link EnableScheduling} turns on Spring's default
 * single-threaded scheduler. The auto-renewal job is
 * annotated with {@code @SchedulerLock} (ShedLock) so it
 * fires from exactly one pod at a time when billing-service
 * is scaled out.</p>
 *
 * <p>{@link EnableSchedulerLock} is the ShedLock side of
 * the same concern. It scans the classpath for beans
 * annotated with {@code @SchedulerLock} and wraps their
 * execution in a JDBC-backed distributed lock.</p>
 *
 * <p>{@link ConfigurationPropertiesScan} picks up
 * {@link ua.fin.billing.config.BillingProperties} without
 * needing an explicit {@code @EnableConfigurationProperties}
 * declaration. Mirrors how other services in the meta-repo
 * are wired.</p>
 */
@SpringBootApplication
@EntityScan("ua.fin.billing.entity")
@ConfigurationPropertiesScan("ua.fin.billing.config")
@EnableScheduling
@EnableDiscoveryClient
@EnableSchedulerLock(defaultLockAtMostFor = "PT1H")
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
