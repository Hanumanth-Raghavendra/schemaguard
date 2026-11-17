package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/**
 * A throwaway database on the PostgreSQL server the engine's oracle tests use, switched on by
 * {@code SG_PG_ORACLE=1} and found through {@code PGHOST}, {@code PGPORT}, {@code PGUSER} and
 * {@code PGPASSWORD}. Persistence is tested on the real thing, never on an in-memory stand-in
 * that would accept SQL PostgreSQL refuses.
 */
public final class TestDatabase {

    public static final String SWITCH = "SG_PG_ORACLE";

    private final String host;
    private final String port;
    private final String user;
    private final String password;
    private final String name;

    private TestDatabase(String name) {
        Map<String, String> env = System.getenv();
        String pgHost = env.getOrDefault("PGHOST", "localhost");
        // A PGHOST that is a socket directory is of no use to JDBC; the server listens on TCP as well.
        this.host = pgHost.startsWith("/") ? "127.0.0.1" : pgHost;
        this.port = env.getOrDefault("PGPORT", "5432");
        this.user = env.getOrDefault("PGUSER", "postgres");
        this.password = env.getOrDefault("PGPASSWORD", "");
        this.name = name;
    }

    /** Creates a new, empty database with a name of its own. */
    public static TestDatabase create(String prefix) {
        TestDatabase database = new TestDatabase(prefix + "_" + Long.toString(System.nanoTime(), 36));
        database.admin("CREATE DATABASE " + database.name);
        return database;
    }

    public String url() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + name;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** Drops the database even while a pool still holds connections to it. */
    public void drop() {
        admin("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    private void admin(String sql) {
        Properties login = new Properties();
        login.setProperty("user", user);
        login.setProperty("password", password);
        try (Connection connection = DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/postgres", login);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot reach the test PostgreSQL server: " + e.getMessage(), e);
        }
    }
}
