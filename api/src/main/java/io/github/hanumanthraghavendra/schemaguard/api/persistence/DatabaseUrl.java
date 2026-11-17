package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A database connection, read from either a JDBC URL or a libpq connection string.
 *
 * <p>Hosted PostgreSQL services such as Neon hand out strings like
 * {@code postgresql://user:secret@host/db?sslmode=require}. The JDBC driver wants
 * {@code jdbc:postgresql://host/db?sslmode=require} with the user and password apart. This
 * accepts both, so the connection string can be pasted as it is.
 *
 * @param jdbcUrl  the URL for the JDBC driver, without credentials
 * @param username the user, if known
 * @param password the password, if known
 */
public record DatabaseUrl(String jdbcUrl, Optional<String> username, Optional<String> password) {

    /** libpq parameters the JDBC driver does not know; they are dropped. */
    private static final List<String> UNKNOWN_TO_JDBC = List.of("channel_binding");

    /**
     * @param url      a JDBC URL or a {@code postgres://} / {@code postgresql://} string
     * @param username used when the string carries no user
     * @param password used when the string carries no password
     * @throws IllegalArgumentException if the URL is neither; the message never repeats it,
     *     as it may contain a password
     */
    public static DatabaseUrl parse(String url, String username, String password) {
        String trimmed = url == null ? "" : url.strip();
        Optional<String> user = Optional.ofNullable(username).filter(u -> !u.isBlank());
        Optional<String> secret = Optional.ofNullable(password).filter(p -> !p.isBlank());
        if (trimmed.startsWith("jdbc:postgresql:")) {
            return new DatabaseUrl(trimmed, user, secret);
        }
        if (!trimmed.startsWith("postgresql://") && !trimmed.startsWith("postgres://")) {
            throw new IllegalArgumentException(
                    "The database URL must start with jdbc:postgresql:, postgresql:// or postgres://");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The database URL is not a valid connection string");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("The database URL names no host");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String own = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            user = Optional.of(own).filter(u -> !u.isEmpty()).or(() -> Optional.ofNullable(username).filter(u -> !u.isBlank()));
            if (colon >= 0) {
                secret = Optional.of(decode(userInfo.substring(colon + 1)));
            }
        }
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        jdbc.append(path == null || path.isEmpty() ? "/" : path);
        String query = query(uri.getRawQuery());
        if (!query.isEmpty()) {
            jdbc.append('?').append(query);
        }
        return new DatabaseUrl(jdbc.toString(), user, secret);
    }

    private static String query(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : raw.split("&")) {
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            if (!pair.isEmpty() && !UNKNOWN_TO_JDBC.contains(key)) {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "DatabaseUrl[" + jdbcUrl + ", user=" + username.orElse("(none)") + "]";
    }
}
