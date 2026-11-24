package io.github.hanumanthraghavendra.schemaguard.api.projects;

import io.github.hanumanthraghavendra.schemaguard.api.persistence.Database;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Projects and schema versions in PostgreSQL (tables {@code project} and {@code schema_version},
 * V3). Every query about a project names its account too, so one account can never reach
 * another's projects, whatever id it sends.
 */
public class ProjectStore {

    private static final String FEATURE = "Projects";

    private static final String VERSION_COLUMNS =
            "v.project_id, v.number, v.label, v.size_bytes, v.created_at, v.risk_score, v.risk_level, v.risk_reasons, v.changes, v.findings";

    /**
     * How much is stored: for the limits a new version must stay within.
     *
     * @param versions     versions of the project
     * @param accountBytes schema text of all the account's versions
     * @param totalBytes   schema text of every version of every account
     */
    public record Usage(int versions, long accountBytes, long totalBytes) {}

    private final Database database;
    private final JdbcClient jdbc;

    public ProjectStore(Database database) {
        this.database = database;
        this.jdbc = database.jdbc();
    }

    /** Runs the work in one transaction; an exception rolls it all back. */
    public <T> T transaction(Supplier<T> work) {
        return database.transaction(FEATURE, work);
    }

    /** The account's projects, most recently changed first. */
    public List<ProjectListing> list(UUID account) {
        return database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                SELECT p.id, p.account_id, p.name, p.created_at,
                                       (SELECT count(*) FROM schema_version c WHERE c.project_id = p.id) AS version_count,
                                       %s
                                FROM project p
                                LEFT JOIN LATERAL (
                                    SELECT project_id, number, label, size_bytes, created_at, risk_score, risk_level, risk_reasons, changes, findings
                                    FROM schema_version l WHERE l.project_id = p.id ORDER BY l.number DESC LIMIT 1
                                ) v ON true
                                WHERE p.account_id = :account
                                ORDER BY coalesce(v.created_at, p.created_at) DESC, p.name
                                """
                                        .formatted(VERSION_COLUMNS))
                        .param("account", account)
                        .query((row, number) -> new ProjectListing(
                                project(row), row.getInt("version_count"), row.getObject("number") == null ? null : version(row)))
                        .list());
    }

    public int count(UUID account) {
        return database.run(FEATURE, () -> jdbc.sql("SELECT count(*) FROM project WHERE account_id = :account")
                .param("account", account)
                .query(Integer.class)
                .single());
    }

    public Optional<Project> find(UUID account, UUID id) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT id, account_id, name, created_at FROM project WHERE id = :id AND account_id = :account")
                        .param("id", id)
                        .param("account", account)
                        .query((row, number) -> project(row))
                        .optional());
    }

    /**
     * The project, locked until the transaction ends, so versions are added to it one at a time
     * and their numbers never collide. Call it inside {@link #transaction}.
     */
    public Optional<Project> lock(UUID account, UUID id) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT id, account_id, name, created_at FROM project WHERE id = :id AND account_id = :account FOR UPDATE")
                        .param("id", id)
                        .param("account", account)
                        .query((row, number) -> project(row))
                        .optional());
    }

    /**
     * Locks the account's row until the transaction ends, so its projects are created and renamed
     * one at a time and the limit and name checks cannot race. Call it inside {@link #transaction}.
     *
     * @return false if the account no longer exists (deleted while its access token was valid)
     */
    public boolean lockAccount(UUID account) {
        return database.run(FEATURE, () -> jdbc.sql("SELECT id FROM account WHERE id = :account FOR UPDATE")
                .param("account", account)
                .query(UUID.class)
                .optional()
                .isPresent());
    }

    /** True if another project of the account has this name, in any case. */
    public boolean nameTaken(UUID account, String name, @Nullable UUID except) {
        return database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                SELECT count(*) FROM project
                                WHERE account_id = :account AND lower(name) = lower(:name) AND id IS DISTINCT FROM CAST(:except AS uuid)
                                """)
                        .param("account", account)
                        .param("name", name)
                        .param("except", except)
                        .query(Integer.class)
                        .single() > 0);
    }

    public void insert(Project project) {
        database.run(FEATURE, () -> jdbc.sql("INSERT INTO project (id, account_id, name, created_at) VALUES (:id, :account, :name, :created)")
                .param("id", project.id())
                .param("account", project.accountId())
                .param("name", project.name())
                .param("created", time(project.createdAt()))
                .update());
    }

    /** False if the account has no such project. */
    public boolean rename(UUID account, UUID id, String name) {
        return database.run(FEATURE, () -> jdbc.sql("UPDATE project SET name = :name WHERE id = :id AND account_id = :account")
                .param("id", id)
                .param("account", account)
                .param("name", name)
                .update() > 0);
    }

    /** Deletes a project and, through the foreign key, all its versions. */
    public boolean delete(UUID account, UUID id) {
        return database.run(FEATURE, () -> jdbc.sql("DELETE FROM project WHERE id = :id AND account_id = :account")
                .param("id", id)
                .param("account", account)
                .update() > 0);
    }

    /** The project's versions, newest first, without their SQL. */
    public List<SavedVersion> versions(UUID project) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM schema_version v WHERE v.project_id = :project ORDER BY v.number DESC")
                        .param("project", project)
                        .query((row, number) -> version(row))
                        .list());
    }

    public Optional<SavedVersion> version(UUID project, int number) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM schema_version v WHERE v.project_id = :project AND v.number = :number")
                        .param("project", project)
                        .param("number", number)
                        .query((row, n) -> version(row))
                        .optional());
    }

    /** The SQL of a version. */
    public Optional<String> sql(UUID project, int number) {
        return database.run(
                FEATURE,
                () -> jdbc.sql("SELECT schema_sql FROM schema_version WHERE project_id = :project AND number = :number")
                        .param("project", project)
                        .param("number", number)
                        .query(String.class)
                        .optional());
    }

    /** The number of the project's latest version, or 0 if it has none. */
    public int latestNumber(UUID project) {
        return database.run(FEATURE, () -> jdbc.sql("SELECT coalesce(max(number), 0) FROM schema_version WHERE project_id = :project")
                .param("project", project)
                .query(Integer.class)
                .single());
    }

    public Usage usage(UUID account, UUID project) {
        return database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                SELECT (SELECT count(*) FROM schema_version WHERE project_id = :project) AS versions,
                                       (SELECT coalesce(sum(v.size_bytes), 0) FROM schema_version v
                                            JOIN project p ON p.id = v.project_id WHERE p.account_id = :account) AS account_bytes,
                                       (SELECT coalesce(sum(size_bytes), 0) FROM schema_version) AS total_bytes
                                """)
                        .param("account", account)
                        .param("project", project)
                        .query((row, number) -> new Usage(row.getInt("versions"), row.getLong("account_bytes"), row.getLong("total_bytes")))
                        .single());
    }

    public void addVersion(SavedVersion version, String sql) {
        SavedVersion.Risk risk = version.risk();
        database.run(
                FEATURE,
                () -> jdbc.sql(
                                """
                                INSERT INTO schema_version (project_id, number, label, schema_sql, size_bytes, created_at,
                                                            risk_score, risk_level, risk_reasons, changes, findings)
                                VALUES (:project, :number, :label, :sql, :size, :created, :score, :level, :reasons, :changes, :findings)
                                """)
                        .param("project", version.projectId())
                        .param("number", version.number())
                        .param("label", version.label())
                        .param("sql", sql)
                        .param("size", version.sizeBytes())
                        .param("created", time(version.createdAt()))
                        .param("score", risk == null ? null : (short) risk.score())
                        .param("level", risk == null ? null : risk.level())
                        .param("reasons", risk == null ? null : risk.reasons().toArray(String[]::new))
                        .param("changes", risk == null ? null : risk.changes())
                        .param("findings", risk == null ? null : risk.findings())
                        .update());
    }

    public boolean deleteVersion(UUID project, int number) {
        return database.run(FEATURE, () -> jdbc.sql("DELETE FROM schema_version WHERE project_id = :project AND number = :number")
                .param("project", project)
                .param("number", number)
                .update() > 0);
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Project project(ResultSet row) throws SQLException {
        return new Project(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class), row.getString("name"), instant(row, "created_at"));
    }

    private static SavedVersion version(ResultSet row) throws SQLException {
        SavedVersion.@Nullable Risk risk = null;
        if (row.getObject("risk_score") != null) {
            risk = new SavedVersion.Risk(
                    row.getInt("risk_score"), row.getString("risk_level"), reasons(row.getArray("risk_reasons")), row.getInt("changes"), row.getInt("findings"));
        }
        return new SavedVersion(
                row.getObject("project_id", UUID.class),
                row.getInt("number"),
                row.getString("label"),
                row.getInt("size_bytes"),
                instant(row, "created_at"),
                risk);
    }

    private static List<String> reasons(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
