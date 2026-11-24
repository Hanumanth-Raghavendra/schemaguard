package io.github.hanumanthraghavendra.schemaguard.api.projects;

import io.github.hanumanthraghavendra.schemaguard.api.auth.SessionEndedException;
import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.StorageFullException;
import io.github.hanumanthraghavendra.schemaguard.api.service.AnalysisService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Projects and their schema versions, always on behalf of one account.
 *
 * <p>A version is a whole schema (what {@code pg_dump --schema-only} prints, or the "after" of an
 * analysis). Saving one compares it with the latest version and stores the risk of that change, so
 * the timeline shows how risky each step was without analysing anything again. A version
 * identical to the latest is refused: a CI job saving on every push keeps only real changes.
 * Only the latest version can be deleted, because each version's risk is measured against the one
 * before it.
 *
 * <p>Another account's project answers exactly like a missing one (404), so ids reveal nothing.
 */
@Service
public class ProjectService {

    static final int MAX_NAME = 80;
    static final int MAX_LABEL = 120;
    private static final String NOT_FOUND = "There is no such project in this account.";

    private final ObjectProvider<ProjectStore> stores;
    private final AnalysisService analysis;
    private final ApiProperties.Projects limits;
    private final Clock clock;

    /**
     * A project with its versions, newest first.
     */
    public record Detail(Project project, List<SavedVersion> versions) {}

    /**
     * One version with its SQL and the SQL of the version before it.
     *
     * @param previousSql null for the first version
     */
    public record Opened(SavedVersion version, String sql, @Nullable String previousSql) {}

    @Autowired
    public ProjectService(ObjectProvider<ProjectStore> stores, AnalysisService analysis, ApiProperties properties) {
        this(stores, analysis, properties.projects(), Clock.systemUTC());
    }

    ProjectService(ObjectProvider<ProjectStore> stores, AnalysisService analysis, ApiProperties.Projects limits, Clock clock) {
        this.stores = stores;
        this.analysis = analysis;
        this.limits = limits;
        this.clock = clock;
    }

    public List<ProjectListing> list(UUID account) {
        return store().list(account);
    }

    /** Creates an empty project. */
    public Detail create(UUID account, String name) {
        ProjectStore store = store();
        String clean = name(name);
        Project project = new Project(UUID.randomUUID(), account, clean, now());
        store.transaction(() -> {
            if (!store.lockAccount(account)) {
                throw new SessionEndedException("This account no longer exists.");
            }
            if (store.count(account) >= limits.maxProjects()) {
                throw new ProjectLimitException(
                        "This account has " + limits.maxProjects() + " projects, the most it can have. Delete one to make room.");
            }
            if (store.nameTaken(account, clean, null)) {
                throw taken(clean);
            }
            store.insert(project);
            return null;
        });
        return new Detail(project, List.of());
    }

    public Detail get(UUID account, UUID id) {
        ProjectStore store = store();
        Project project = store.find(account, id).orElseThrow(() -> new ProjectNotFoundException(NOT_FOUND));
        return new Detail(project, store.versions(id));
    }

    public Detail rename(UUID account, UUID id, String name) {
        ProjectStore store = store();
        String clean = name(name);
        store.transaction(() -> {
            if (!store.lockAccount(account) || store.find(account, id).isEmpty()) {
                throw new ProjectNotFoundException(NOT_FOUND);
            }
            if (store.nameTaken(account, clean, id)) {
                throw taken(clean);
            }
            store.rename(account, id, clean);
            return null;
        });
        return get(account, id);
    }

    /** Deletes the project with all its versions. */
    public void delete(UUID account, UUID id) {
        if (!store().delete(account, id)) {
            throw new ProjectNotFoundException(NOT_FOUND);
        }
    }

    /**
     * Saves a new version: the next number, with the risk of the change from the latest version.
     *
     * <p>The analysis, the slow part, runs before the transaction, so no lock or connection is held
     * while the engine works. The transaction then checks that no other version was saved in the
     * meantime (optimistic concurrency) and that the limits hold, and inserts.
     *
     * @throws ProjectConflictException if it changes nothing, or another version came first
     * @throws ProjectLimitException    if the project or the account is full
     * @throws StorageFullException     if the service is full
     */
    public SavedVersion addVersion(UUID account, UUID id, String sql, @Nullable String label) {
        ProjectStore store = store();
        String cleanLabel = label(label);
        store.find(account, id).orElseThrow(() -> new ProjectNotFoundException(NOT_FOUND));
        int latest = store.latestNumber(id);
        SavedVersion.Risk risk = null;
        if (latest == 0) {
            analysis.check(sql);
        } else {
            String previous = store.sql(id, latest).orElseThrow(() -> changedMeanwhile());
            AnalysisService.VersionRisk measured = analysis.versionRisk(previous, sql);
            if (measured.changes() == 0) {
                throw new ProjectConflictException(
                        "This schema is the same as version " + latest + ", so there is nothing new to save.");
            }
            risk = new SavedVersion.Risk(
                    measured.score().value(), measured.score().level(), measured.score().reasons(), measured.changes(), measured.findings());
        }
        int size = sql.getBytes(StandardCharsets.UTF_8).length;
        SavedVersion version = new SavedVersion(id, latest + 1, cleanLabel, size, now(), risk);
        return store.transaction(() -> {
            store.lock(account, id).orElseThrow(() -> new ProjectNotFoundException(NOT_FOUND));
            if (store.latestNumber(id) != latest) {
                throw changedMeanwhile();
            }
            ProjectStore.Usage usage = store.usage(account, id);
            if (usage.versions() >= limits.maxVersions()) {
                throw new ProjectLimitException("This project has " + limits.maxVersions()
                        + " versions, the most it can keep. Start a new project for further versions.");
            }
            if (usage.accountBytes() + size > limits.maxAccountBytes()) {
                throw new ProjectLimitException("This account keeps at most " + megabytes(limits.maxAccountBytes())
                        + " of schemas, and this version would go past it. Delete a project to make room.");
            }
            if (usage.totalBytes() + size > limits.maxTotalBytes()) {
                throw new StorageFullException("The service has no room for more schema versions right now; try again later.");
            }
            store.addVersion(version, sql);
            return version;
        });
    }

    public Opened version(UUID account, UUID id, int number) {
        ProjectStore store = store();
        store.find(account, id).orElseThrow(() -> new ProjectNotFoundException(NOT_FOUND));
        SavedVersion version = store.version(id, number).orElseThrow(() -> missingVersion(number));
        String sql = store.sql(id, number).orElseThrow(() -> missingVersion(number));
        String previous = number > 1 ? store.sql(id, number - 1).orElse(null) : null;
        return new Opened(version, sql, previous);
    }

    /** Deletes the latest version; an earlier one would leave the next one's risk measured against nothing. */
    public void deleteVersion(UUID account, UUID id, int number) {
        ProjectStore store = store();
        store.transaction(() -> {
            store.lock(account, id).orElseThrow(() -> new ProjectNotFoundException(NOT_FOUND));
            int latest = store.latestNumber(id);
            if (number < 1 || number > latest) {
                throw missingVersion(number);
            }
            if (number != latest) {
                throw new ProjectConflictException("Only the latest version, " + latest
                        + ", can be deleted: the versions after " + number + " are measured against it.");
            }
            store.deleteVersion(id, number);
            return null;
        });
    }

    /** A project name as stored: trimmed, 1 to 80 characters, one line. */
    static String name(String name) {
        String clean = name.strip();
        int length = clean.codePointCount(0, clean.length());
        if (length == 0 || length > MAX_NAME || clean.chars().anyMatch(Character::isISOControl)) {
            throw new ProjectLimitException("Name the project in 1 to " + MAX_NAME + " characters, on one line.");
        }
        return clean;
    }

    /** A label as stored: trimmed, at most 120 characters, one line; blank means none. */
    static @Nullable String label(@Nullable String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String clean = label.strip();
        if (clean.codePointCount(0, clean.length()) > MAX_LABEL || clean.chars().anyMatch(Character::isISOControl)) {
            throw new ProjectLimitException("Keep the label to one line of at most " + MAX_LABEL + " characters.");
        }
        return clean;
    }

    private static ProjectConflictException taken(String name) {
        return new ProjectConflictException("This account already has a project named \"" + name + "\".");
    }

    private static ProjectConflictException changedMeanwhile() {
        return new ProjectConflictException(
                "Another version of this project was saved or deleted at the same moment. Reload it and save again.");
    }

    private static ProjectNotFoundException missingVersion(int number) {
        return new ProjectNotFoundException("This project has no version " + number + ".");
    }

    private static String megabytes(long bytes) {
        return (bytes / 1_000_000) + " MB";
    }

    private ProjectStore store() {
        ProjectStore store = stores.getIfAvailable();
        if (store == null) {
            throw new PersistenceUnavailableException("Projects are switched off on this server.");
        }
        return store;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
