package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Identifiers and tokens for saved analyses.
 *
 * <p>An id is the only thing protecting a saved analysis, so it is 128 random bits from
 * {@link SecureRandom}, written in base 62 (22 characters, letters and digits, safe in a URL).
 * Guessing one is as likely as guessing a random UUID twice in a row.
 *
 * <p>The delete token is 256 random bits. Only its SHA-256 hash is stored, so a copy of the
 * database is not enough to delete anything.
 */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(62);
    private static final int ID_LENGTH = 22;
    private static final Pattern ID = Pattern.compile("[0-9A-Za-z]{" + ID_LENGTH + "}");

    private Secrets() {}

    public static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        BigInteger value = new BigInteger(1, bytes);
        char[] text = new char[ID_LENGTH];
        for (int i = ID_LENGTH - 1; i >= 0; i--) {
            BigInteger[] division = value.divideAndRemainder(BASE);
            text[i] = DIGITS.charAt(division[1].intValue());
            value = division[0];
        }
        return new String(text);
    }

    /** True if the text has the shape of an id; anything else is not looked up at all. */
    public static boolean isId(String text) {
        return text != null && ID.matcher(text).matches();
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
