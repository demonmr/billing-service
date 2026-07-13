package ua.fin.billing.scheduler;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.fin.billing.service.renewal.AutoRenewalService;

/**
 * Phase 4.3 (Commit 6) — daily auto-renewal
 * scheduler.
 *
 * <p>Default cron: {@code 0 0 3 * * *} — 03:00
 * daily. Configurable via
 * {@code billing.scheduler.auto-renewal-cron}
 * so dev / smoke tests can fire the job every
 * 30 seconds (e.g. a 6-field cron with
 * {@code /30} in the seconds field).</p>
 *
 * <p>{@code @SchedulerLock} from ShedLock
 * prevents double-firing when billing-service
 * is scaled to N pods — only the pod that
 * acquires the JDBC lock runs the sweep. The
 * lock is auto-released after
 * {@code lockAtMostFor} (1h) and held for at
 * least {@code lockAtLeastFor} (5m) — the
 * latter prevents tight-loop re-fires if the
 * job completes in &lt;5 minutes.</p>
 */
@Component
@Slf4j
public class AutoRenewalScheduler {

    private final AutoRenewalService autoRenewalService;

    public AutoRenewalScheduler(AutoRenewalService autoRenewalService) {
        this.autoRenewalService = autoRenewalService;
    }

    @Scheduled(
        cron = "${billing.scheduler.auto-renewal-cron:0 0 3 * * *}"
    )
    @SchedulerLock(
        name = "autoRenewal",
        lockAtMostFor = "PT1H",
        lockAtLeastFor = "PT5M"
    )
    public void runDailyRenewalSweep() {
        final long started = System.currentTimeMillis();
        log.info("Auto-renewal sweep started");
        try {
            final int renewed = autoRenewalService.renewDue();
            final long elapsed = System.currentTimeMillis() - started;
            log.info(
                "Auto-renewal sweep completed: {} renewed in {} ms",
                renewed, elapsed
            );
        } catch (RuntimeException e) {
            // Don't propagate — the @SchedulerLock
            // would otherwise release the lock
            // before the work is done. We log +
            // return; the next scheduled fire
            // (or a manual trigger) can retry.
            log.error("Auto-renewal sweep failed", e);
        }
    }
}
