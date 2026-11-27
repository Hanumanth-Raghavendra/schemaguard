package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import io.github.hanumanthraghavendra.schemaguard.api.auth.AccountStore;
import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.keys.ApiKeyStore;
import io.github.hanumanthraghavendra.schemaguard.api.projects.ProjectStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The database for saved analyses, accounts and projects, present only when
 * {@code schemaguard.persistence.url} is set. Without it the service runs exactly as before and
 * the permalink, account and project endpoints answer 503.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(PersistenceConfig.UrlIsSet.class)
@EnableScheduling
public class PersistenceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceConfig.class);

    /**
     * A pool that never connects before it is used and holds no idle connection for long. A
     * hosted free-tier database that is asleep is woken by the first request that needs it, not
     * by the service starting, and is allowed to fall asleep again.
     */
    @Bean(destroyMethod = "close")
    HikariDataSource analysisDataSource(ApiProperties properties) {
        ApiProperties.Persistence settings = properties.persistence();
        DatabaseUrl url = DatabaseUrl.parse(settings.url(), settings.username(), settings.password());
        HikariConfig config = new HikariConfig();
        config.setPoolName("analyses");
        config.setJdbcUrl(url.jdbcUrl());
        url.username().ifPresent(config::setUsername);
        url.password().ifPresent(config::setPassword);
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(0);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(300_000);
        config.setConnectionTimeout(Math.max(250, settings.connectTimeout().toMillis()));
        config.setInitializationFailTimeout(-1);
        LOG.info("Saved analyses, accounts, projects and API keys are on: {}", url);
        return new HikariDataSource(config);
    }

    /**
     * Migrations get their own connections (D-118): to {@code migration-url} if set, else to Neon's
     * direct host when the URL is its pooler, else to the same URL. Not from the pool, so their
     * lock timeout stays theirs.
     */
    @Bean
    Database database(HikariDataSource analysisDataSource, ApiProperties properties) {
        ApiProperties.Persistence settings = properties.persistence();
        DatabaseUrl url = DatabaseUrl.parse(settings.url(), settings.username(), settings.password());
        DatabaseUrl migrations;
        String where;
        if (settings.migrationUrl() != null && !settings.migrationUrl().isBlank()) {
            migrations = DatabaseUrl.parse(settings.migrationUrl(), settings.username(), settings.password());
            where = "the migration URL";
        } else {
            Optional<DatabaseUrl> direct = url.withoutNeonPooler();
            migrations = direct.orElse(url);
            where = direct.isPresent() ? "Neon's direct host (the pooled one without -pooler)" : "the same URL";
        }
        LOG.info("Migrations connect to {}: {}", where, migrations);
        DriverManagerDataSource connections = new DriverManagerDataSource(migrations.jdbcUrl());
        migrations.username().ifPresent(connections::setUsername);
        migrations.password().ifPresent(connections::setPassword);
        return new Database(analysisDataSource, connections, settings.migrationLockTimeout());
    }

    @Bean
    JdbcAnalysisStore analysisStore(Database database, ApiProperties properties) {
        return new JdbcAnalysisStore(database, properties.persistence(), Clock.systemUTC());
    }

    @Bean
    AccountStore accountStore(Database database) {
        return new AccountStore(database);
    }

    @Bean
    ProjectStore projectStore(Database database) {
        return new ProjectStore(database);
    }

    @Bean
    ApiKeyStore apiKeyStore(Database database) {
        return new ApiKeyStore(database);
    }

    @Bean
    RetentionJob retentionJob(AnalysisStore analysisStore, AccountStore accountStore) {
        return new RetentionJob(analysisStore, accountStore, Clock.systemUTC());
    }

    /** Deletes expired analyses and refresh tokens every hour. */
    public static class RetentionJob {

        private final AnalysisStore analyses;
        private final AccountStore accounts;
        private final Clock clock;

        RetentionJob(AnalysisStore analyses, AccountStore accounts, Clock clock) {
            this.analyses = analyses;
            this.accounts = accounts;
            this.clock = clock;
        }

        @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
        public void deleteExpired() {
            try {
                int analysesDeleted = analyses.deleteExpired();
                int tokensDeleted = accounts.deleteExpiredTokens(clock.instant());
                if (analysesDeleted > 0 || tokensDeleted > 0) {
                    LOG.info("Deleted {} expired saved analyses and {} expired refresh tokens", analysesDeleted, tokensDeleted);
                }
            } catch (PersistenceUnavailableException e) {
                // Already logged by the database; the next run tries again.
            }
        }
    }

    /** Matches when the URL property has text: an empty variable on a hosting service means "off". */
    static class UrlIsSet extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("schemaguard.persistence.url");
            return url != null && !url.isBlank()
                    ? ConditionOutcome.match("schemaguard.persistence.url is set")
                    : ConditionOutcome.noMatch("schemaguard.persistence.url is not set");
        }
    }
}
