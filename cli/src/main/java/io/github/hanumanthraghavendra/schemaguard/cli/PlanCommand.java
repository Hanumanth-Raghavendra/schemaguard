package io.github.hanumanthraghavendra.schemaguard.cli;

import io.github.hanumanthraghavendra.schemaguard.engine.planner.MigrationPlan;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.PlanOptions;
import io.github.hanumanthraghavendra.schemaguard.engine.planner.Planner;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "plan",
        description = "Write the migration from one schema to another: the safe route by default, or the direct one.")
final class PlanCommand extends AnalysisCommand implements Callable<Integer> {

    enum Route {
        SAFE,
        DIRECT
    }

    @Parameters(index = "0", paramLabel = "<before.sql>", description = "The schema now (- for standard input)")
    String before;

    @Parameters(index = "1", paramLabel = "<after.sql>", description = "The schema wanted")
    String after;

    @Option(names = "--route", paramLabel = "<route>", defaultValue = "safe", description = "safe (the default) or direct")
    Route route;

    @Option(
            names = "--lock-timeout",
            paramLabel = "<time>",
            description = "The lock_timeout the safe route sets, such as 5s or 500ms")
    String lockTimeout;

    @Override
    public Integer call() {
        Source[] sources = read(before, after);
        Schemas.Parsed old = Schemas.parse("before", sources[0], io().err());
        Schemas.Parsed wanted = Schemas.parse("after", sources[1], io().err());
        MigrationPlan plan;
        try {
            PlanOptions options = route == Route.DIRECT ? PlanOptions.direct() : PlanOptions.safe();
            if (lockTimeout != null) {
                options = options.withLockTimeout(lockTimeout);
            }
            plan = Planner.plan(old.catalog(), wanted.catalog(), options);
        } catch (IllegalArgumentException e) {
            throw new InputException(e.getMessage());
        }
        Documents.PlanResponse response = Documents.plan(plan, Schemas.warnings(old, wanted));
        print(response::sql, () -> Markdown.plan(response), () -> response);
        return ExitCodes.OK;
    }
}
