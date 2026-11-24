package io.github.hanumanthraghavendra.schemaguard.api.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.Database;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.StorageFullException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import io.github.hanumanthraghavendra.schemaguard.api.service.AnalysisService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Limits and concurrent saves, on a real PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
class ProjectServiceTest {

    private static TestDatabase database;
    private static DriverManagerDataSource dataSource;
    private static ProjectStore store;
    private static AnalysisService analysis;

    @BeforeAll
    static void createDatabase() {
        database = TestDatabase.create("sg_projects_test");
        dataSource = new DriverManagerDataSource(database.url(), database.user(), database.password());
        store = new ProjectStore(new Database(dataSource));
        // The store migrates on first use; projects need an account to belong to.
        store.list(UUID.randomUUID());
        // The analysis limits as configured by default.
        analysis = new AnalysisService(Binder.get(new StandardEnvironment()).bindOrCreate("schemaguard", ApiProperties.class));
    }

    @AfterAll
    static void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    private static ProjectService service(ApiProperties.Projects limits) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("projectStore", store));
        return new ProjectService(beans.getBeanProvider(ProjectStore.class), analysis, limits, Clock.systemUTC());
    }

    private static UUID account() {
        UUID id = UUID.randomUUID();
        JdbcClient.create(dataSource)
                .sql("INSERT INTO account (id, email, password_hash, created_at) VALUES (:id, :email, 'x', :now)")
                .param("id", id)
                .param("email", id + "@example.com")
                .param("now", Instant.now().atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private static String table(int columns) {
        StringBuilder sql = new StringBuilder("CREATE TABLE t (id integer PRIMARY KEY");
        for (int i = 1; i < columns; i++) {
            sql.append(", c").append(i).append(" text");
        }
        return sql.append(");").toString();
    }

    @Test
    void anAccountKeepsAtMostSoManyProjects() {
        ProjectService service = service(new ApiProperties.Projects(2, 500, 20_000_000, 200_000_000));
        UUID account = account();
        service.create(account, "one");
        service.create(account, "two");
        assertThatThrownBy(() -> service.create(account, "three"))
                .isInstanceOf(ProjectLimitException.class)
                .hasMessage("This account has 2 projects, the most it can have. Delete one to make room.");
        // Another account has its own allowance.
        assertThat(service.create(account(), "three").project().name()).isEqualTo("three");
    }

    @Test
    void aProjectKeepsAtMostSoManyVersions() {
        ProjectService service = service(new ApiProperties.Projects(20, 2, 20_000_000, 200_000_000));
        UUID account = account();
        UUID project = service.create(account, "shop").project().id();
        service.addVersion(account, project, table(1), null);
        service.addVersion(account, project, table(2), null);
        assertThatThrownBy(() -> service.addVersion(account, project, table(3), null))
                .isInstanceOf(ProjectLimitException.class)
                .hasMessageStartingWith("This project has 2 versions, the most it can keep.");
    }

    @Test
    void anAccountKeepsAtMostSoMuchSchemaText() {
        ProjectService service = service(new ApiProperties.Projects(20, 500, 2_000_000, 200_000_000));
        UUID account = account();
        UUID project = service.create(account, "shop").project().id();
        // Three versions of 900 kB each: the third would take the account past 2 MB.
        String padding = "\n-- " + "x".repeat(900_000);
        service.addVersion(account, project, table(1) + padding, null);
        service.addVersion(account, project, table(2) + padding, null);
        assertThatThrownBy(() -> service.addVersion(account, project, table(3) + padding, null))
                .isInstanceOf(ProjectLimitException.class)
                .hasMessageStartingWith("This account keeps at most 2 MB of schemas");
    }

    @Test
    void theServiceStopsSavingWhenAllAccountsTogetherFillIt() {
        ProjectService service = service(new ApiProperties.Projects(20, 500, 20_000_000, 1_024));
        UUID account = account();
        UUID project = service.create(account, "shop").project().id();
        assertThatThrownBy(() -> service.addVersion(account, project, table(1) + "\n-- " + "x".repeat(2_000), null))
                .isInstanceOf(StorageFullException.class)
                .hasMessage("The service has no room for more schema versions right now; try again later.");
    }

    @Test
    void versionsSavedAtTheSameMomentGetDistinctNumbersOrAClearConflict() throws Exception {
        ProjectService service = service(new ApiProperties.Projects(20, 500, 20_000_000, 200_000_000));
        UUID account = account();
        UUID project = service.create(account, "shop").project().id();
        service.addVersion(account, project, table(1), null);

        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> saved = Collections.synchronizedList(new ArrayList<>());
        List<String> refused = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String sql = table(2 + i);
                done.add(pool.submit(() -> {
                    start.await();
                    try {
                        saved.add(service.addVersion(account, project, sql, null).number());
                    } catch (ProjectConflictException e) {
                        refused.add(e.getMessage());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        }
        assertThat(saved).isNotEmpty().doesNotHaveDuplicates();
        assertThat(saved.size() + refused.size()).isEqualTo(writers);
        assertThat(refused).allMatch(m -> m.startsWith("Another version of this project was saved or deleted at the same moment."));
        List<Integer> stored = service.get(account, project).versions().stream().map(SavedVersion::number).toList();
        assertThat(stored).hasSize(1 + saved.size());
        assertThat(stored.reversed()).isEqualTo(IntStream.rangeClosed(1, stored.size()).boxed().toList());
    }

    @Test
    void withoutADatabaseProjectsAreSwitchedOff() {
        ProjectService off = new ProjectService(
                new StaticListableBeanFactory(Map.of()).getBeanProvider(ProjectStore.class),
                analysis,
                new ApiProperties.Projects(20, 500, 20_000_000, 200_000_000),
                Clock.systemUTC());
        assertThatThrownBy(() -> off.list(UUID.randomUUID()))
                .isInstanceOf(PersistenceUnavailableException.class)
                .hasMessage("Projects are switched off on this server.");
    }

    @Test
    void namesAndLabelsAreTrimmedAndKeptToOneLine() {
        assertThat(ProjectService.name("  shop  ")).isEqualTo("shop");
        assertThat(ProjectService.label("   ")).isNull();
        assertThat(ProjectService.label(null)).isNull();
        assertThat(ProjectService.label(" v2.4 ")).isEqualTo("v2.4");
        assertThatThrownBy(() -> ProjectService.label("one\ttwo")).isInstanceOf(ProjectLimitException.class);
    }
}
