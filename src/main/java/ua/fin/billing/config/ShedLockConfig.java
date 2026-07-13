package ua.fin.billing.config;

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Phase 4.3 (Commit 6) — ShedLock JDBC
 * lock provider. The provider needs an
 * explicit {@code @Bean} declaration;
 * {@code @EnableSchedulerLock} on
 * {@code BillingApplication} picks it up.
 *
 * <p>The table name is left at the
 * provider's default ({@code shedlock}) —
 * the table itself is created by the
 * {@code V24__shedlock_table.sql} Flyway
 * migration in db-migration-service. The
 * provider uses the app's clock (not the DB
 * clock); cross-pod clock skew is a Phase 5+
 * concern (we'll switch to {@code DATETIME2}
 * in SQL Server + app clock sync via
 * Eureka's time-service).</p>
 */
@Configuration
public class ShedLockConfig {

    @Bean
    public JdbcTemplateLockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
