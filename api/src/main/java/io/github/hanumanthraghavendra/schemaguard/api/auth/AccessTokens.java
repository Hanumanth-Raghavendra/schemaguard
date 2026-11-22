package io.github.hanumanthraghavendra.schemaguard.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Access tokens: JWTs in the profile of RFC 9068 (JWT Profile for OAuth 2.0 Access Tokens),
 * signed with HMAC-SHA256 by this service and checked by it. The header says {@code typ: at+jwt};
 * the claims are the issuer, the audience, the client, the account as subject, issue and expiry
 * times, and a unique id. Nothing else: the token is not a place for the email or anything private.
 */
public final class AccessTokens {

    public static final String ISSUER = "schemaguard";
    public static final String AUDIENCE = "schemaguard-api";
    public static final String CLIENT_ID = "schemaguard-web";
    static final String TYPE = "at+jwt";
    static final int MIN_SECRET_LENGTH = 32;

    private final JwtEncoder encoder;
    private final Duration lifetime;
    private final Clock clock;

    /**
     * A token, and how many seconds it is accepted for.
     *
     * @param value     the compact JWT, for {@code Authorization: Bearer}
     * @param expiresIn seconds until it expires
     */
    public record Issued(String value, long expiresIn) {}

    public AccessTokens(JwtEncoder encoder, Duration lifetime, Clock clock) {
        this.encoder = encoder;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public Issued issue(UUID account) {
        Instant now = clock.instant();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .audience(List.of(AUDIENCE))
                        .claim("client_id", CLIENT_ID)
                        .subject(account.toString())
                        .issuedAt(now)
                        .expiresAt(now.plus(lifetime))
                        .id(UUID.randomUUID().toString())
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type(TYPE).build();
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new Issued(jwt.getTokenValue(), lifetime.toSeconds());
    }

    /** Everything an access token must satisfy beyond its signature: RFC 9068's checks with our values. */
    public static OAuth2TokenValidator<Jwt> validator() {
        return JwtValidators.createAtJwtValidator().issuer(ISSUER).audience(AUDIENCE).clientId(CLIENT_ID).build();
    }

    /**
     * The signing key. A configured secret (Render generates a random 256-bit one) is hashed with
     * SHA-256 into exactly the 256 bits HS256 uses, so any format works; without one, a random key
     * lives as long as the process.
     *
     * @throws IllegalStateException if a secret is set but shorter than 32 characters
     */
    public static SecretKey key(String secret) {
        byte[] bytes;
        if (secret == null || secret.isBlank()) {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "schemaguard.auth.jwt-secret must have at least " + MIN_SECRET_LENGTH + " characters (it has " + secret.length() + ")");
        } else {
            try {
                bytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Every Java runtime has SHA-256", e);
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
