package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The database for saved analyses, present only when {@code schemaguard.persistence.url} is
 * set. Without it the service runs exactly as before and the permalink endpoints answer 503.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(PersistenceConfig.UrlIsSet.class)
@EnableScheduling
public class PersistenceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceConfig.class);

    /**
     * A pool that never connects before it is used and holds no idle connection for long. A
     * hosted free-tier database that is asleep is woken by the first permalink request, not
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
        LOG.info("Saved analyses are on: {}", url);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcAnalysisStore analysisStore(HikariDataSource analysisDataSource, ApiProperties properties) {
        return new JdbcAnalysisStore(analysisDataSource, properties.persistence(), Clock.systemUTC());
    }

    @Bean
    RetentionJob retentionJob(AnalysisStore analysisStore) {
        return new RetentionJob(analysisStore);
    }

    /** Deletes expired analyses every hour. */
    public static class RetentionJob {

        private final AnalysisStore store;

        RetentionJob(AnalysisStore store) {
            this.store = store;
        }

        @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
        public void deleteExpired() {
            try {
                int deleted = store.deleteExpired();
                if (deleted > 0) {
                    LOG.info("Deleted {} expired saved analyses", deleted);
                }
            } catch (PersistenceUnavailableException e) {
                // Already logged by the store; the next run tries again.
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
