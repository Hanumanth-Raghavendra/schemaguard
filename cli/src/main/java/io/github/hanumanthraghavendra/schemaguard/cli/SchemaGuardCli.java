package io.github.hanumanthraghavendra.schemaguard.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/** The {@code schemaguard} command. Each analysis runs offline on the engine; only {@code save} uses the network. */
@Command(
        name = "schemaguard",
        mixinStandardHelpOptions = true,
        scope = ScopeType.INHERIT,
        versionProvider = SchemaGuardCli.Version.class,
        description = {
            "Checks PostgreSQL schema changes before they ship: what changed, what it will lock or break, and how to ship it safely.",
            "Every analysis runs offline; only save talks to the SchemaGuard API."
        },
        subcommands = {
            DiffCommand.class,
            ReportCommand.class,
            PlanCommand.class,
            LintCommand.class,
            SaveCommand.class,
            CommandLine.HelpCommand.class
        },
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:Done; the risk is within --fail-above, if given",
            "1:The risk is above --fail-above, or a checked migration has a statement PostgreSQL would refuse",
            "2:A usage error, or input that cannot be analysed",
            "3:The SchemaGuard API refused the request or could not be reached (save)",
            "4:A bug in SchemaGuard; please report it"
        })
public final class SchemaGuardCli implements Callable<Integer> {

    private final Io io;

    @Spec
    CommandSpec spec;

    SchemaGuardCli(Io io) {
        this.io = io;
    }

    Io io() {
        return io;
    }

    /** Without a command: the usage, and the exit code of a usage error. */
    @Override
    public Integer call() {
        spec.commandLine().usage(io.err());
        return ExitCodes.BAD_INPUT;
    }

    public static void main(String[] args) {
        System.exit(run(args, Io.system()));
    }

    static int run(String[] args, Io io) {
        CommandLine cli = new CommandLine(new SchemaGuardCli(io));
        cli.setOut(io.out());
        cli.setErr(io.err());
        cli.setCaseInsensitiveEnumValuesAllowed(true);
        cli.setExecutionExceptionHandler((exception, command, parsed) -> failed(exception, io));
        try {
            return cli.execute(args);
        } finally {
            io.out().flush();
            io.err().flush();
        }
    }

    private static int failed(Exception exception, Io io) {
        switch (exception) {
            case InputException e -> {
                io.err().println("schemaguard: " + e.getMessage());
                return ExitCodes.BAD_INPUT;
            }
            case ApiException e -> {
                io.err().println("schemaguard: " + e.getMessage());
                return ExitCodes.API_FAILED;
            }
            default -> {
                io.err().println("schemaguard: unexpected error, please report it at "
                        + "https://github.com/Hanumanth-Raghavendra/schemaguard/issues");
                exception.printStackTrace(io.err());
                return ExitCodes.INTERNAL;
            }
        }
    }

    /** The version Maven wrote into {@code schemaguard-cli.properties}. */
    static final class Version implements IVersionProvider {
        @Override
        public String[] getVersion() throws IOException {
            Properties properties = new Properties();
            try (InputStream in = SchemaGuardCli.class.getResourceAsStream("/schemaguard-cli.properties")) {
                if (in != null) {
                    properties.load(in);
                }
            }
            return new String[] {
                "SchemaGuard " + properties.getProperty("version", "(unknown version)"),
                "Java " + Runtime.version() + ", " + System.getProperty("java.vendor", "")
            };
        }
    }
}
