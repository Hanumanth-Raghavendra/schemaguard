package io.github.hanumanthraghavendra.schemaguard.api.service;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Permalinks;
import io.github.hanumanthraghavendra.schemaguard.api.dto.Requests;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.AnalysisStore;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.Secrets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves analyses and opens them again by id. Nothing is saved unless a client asks for it
 * through these methods: the plain analysis endpoints never store anything.
 */
@Service
public class PermalinkService {

    private final AnalysisService analyses;
    private final ObjectProvider<AnalysisStore> store;
    private final JsonMapper json;
    private final ApiProperties.Persistence settings;
    private final String engineVersion;
    private final Clock clock;

    public PermalinkService(
            AnalysisService analyses,
            ObjectProvider<AnalysisStore> store,
            JsonMapper json,
            ApiProperties properties,
            ObjectProvider<BuildProperties> build) {
        this.analyses = analyses;
        this.store = store;
        this.json = json;
        this.settings = properties.persistence();
        BuildProperties info = build.getIfAvailable();
        this.engineVersion = info == null ? "development" : info.getVersion();
        this.clock = Clock.systemUTC();
    }

    public Permalinks.Saved saveDiff(Requests.Compare request) {
        return save("diff", request, analyses::diff);
    }

    public Permalinks.Saved saveReport(Requests.Compare request) {
        return save("report", request, analyses::report);
    }

    public Permalinks.Saved savePlan(Requests.Plan request) {
        return save("plan", request, analyses::plan);
    }

    public Permalinks.Saved saveLint(Requests.Lint request) {
        return save("lint", request, analyses::lint);
    }

    public Permalinks.Stored open(String id) {
        AnalysisStore.Saved saved =
                (Secrets.isId(id) ? store().find(id) : Optional.<AnalysisStore.Saved>empty())
                        .orElseThrow(() -> new AnalysisNotFoundException("No saved analysis has this id, or it has expired."));
        return new Permalinks.Stored(
                saved.id(), saved.kind(), saved.createdAt(), saved.expiresAt(), saved.engineVersion(), saved.request(), saved.result());
    }

    public void delete(String id, String token) {
        if (!Secrets.isId(id)) {
            throw new AnalysisNotFoundException("No saved analysis has this id, or it has expired.");
        }
        AnalysisStore.Deletion outcome = store().delete(id, Secrets.hash(token == null ? "" : token));
        switch (outcome) {
            case DELETED -> {
                // Done.
            }
            case NOT_FOUND -> throw new AnalysisNotFoundException("No saved analysis has this id, or it has expired.");
            case WRONG_TOKEN -> throw new WrongTokenException("The delete token does not match this analysis.");
        }
    }

    /**
     * Runs the analysis first, so a request that fails (422, 413) is never stored, then stores
     * the request and the result together.
     */
    private <Q, R> Permalinks.Saved save(String kind, Q request, Function<Q, R> analysis) {
        AnalysisStore store = store();
        R result = analysis.apply(request);
        String requestJson = json.writeValueAsString(request);
        String resultJson = json.writeValueAsString(result);
        long size = (long) requestJson.length() + resultJson.length();
        if (size > settings.maxAnalysisBytes()) {
            throw new LimitExceededException(
                    "This analysis takes " + size + " bytes; at most " + settings.maxAnalysisBytes() + " can be saved.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String id = Secrets.newId();
        String token = Secrets.newToken();
        AnalysisStore.Saved saved =
                new AnalysisStore.Saved(id, kind, requestJson, resultJson, engineVersion, now, now.plus(settings.retention()));
        store.save(saved, Secrets.hash(token));
        return new Permalinks.Saved(id, kind, "/api/v1/analyses/" + id, now, saved.expiresAt(), token, resultJson);
    }

    private AnalysisStore store() {
        AnalysisStore available = store.getIfAvailable();
        if (available == null) {
            throw new PersistenceUnavailableException("Saved analyses are switched off on this server.");
        }
        return available;
    }
}
