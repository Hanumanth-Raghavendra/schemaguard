package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What an email and a password must be, following OWASP ASVS 5.0 (V6.2) and NIST SP 800-63B:
 * a minimum length and a list of common passwords instead of composition rules ("one digit, one
 * symbol"), which make passwords harder to remember and no harder to guess. Spaces and every
 * Unicode character are allowed, and nothing is trimmed: a passphrase is welcome as typed.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 256;
    public static final int MAX_EMAIL_LENGTH = 254;

    private static final String COMMON_PASSWORDS = "/auth/common-passwords.txt";
    // One @, something on both sides, a dot in the domain, no spaces. Deliverability is not checked here.
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = load();
    }

    /**
     * The email as it is stored: trimmed and lowercased, so {@code You@Example.com} and
     * {@code you@example.com} are one account.
     *
     * @throws InvalidAccountDetailsException if it does not look like an email address
     */
    public String normalizeEmail(String email) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(normalized).matches()) {
            throw new InvalidAccountDetailsException("Enter an email address such as you@example.com.");
        }
        return normalized;
    }

    /**
     * Refuses a password that is too short, too long, common, or the email address itself.
     *
     * @param email the normalized email of the account
     * @throws InvalidAccountDetailsException with a message that says what to change
     */
    public void check(String password, String email) {
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            throw new InvalidAccountDetailsException(
                    "Use at least " + MIN_LENGTH + " characters. A few unrelated words make a strong password that is easy to remember.");
        }
        if (length > MAX_LENGTH) {
            throw new InvalidAccountDetailsException("Use at most " + MAX_LENGTH + " characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (common.contains(lower)) {
            throw new InvalidAccountDetailsException(
                    "This password is one of the 10,000 most common ones, so it is among the first an attacker tries. Choose another.");
        }
        String localPart = email.substring(0, email.indexOf('@'));
        if (lower.equals(email) || lower.equals(localPart)) {
            throw new InvalidAccountDetailsException("Do not use your email address as your password.");
        }
    }

    /** How many common passwords are refused. */
    int commonPasswords() {
        return common.size();
    }

    private static Set<String> load() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(COMMON_PASSWORDS)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + COMMON_PASSWORDS);
            }
            Set<String> words = new HashSet<>(16_384);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    words.add(line);
                }
            }
            return Set.copyOf(words);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
