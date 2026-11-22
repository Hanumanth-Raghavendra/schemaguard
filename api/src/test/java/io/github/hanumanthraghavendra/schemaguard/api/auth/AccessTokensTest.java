package io.github.hanumanthraghavendra.schemaguard.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Access tokens are accepted only as this service issues them: signed by its key, typed, unexpired. */
class AccessTokensTest {

    private static final String SECRET = "B0jrphAPOY7pg92AN0c9MN4yecczLMdwnx4OkA1KFUk=";
    private static final UUID ACCOUNT = UUID.fromString("6f1f3a4e-1d2b-4c5d-8e9f-0a1b2c3d4e5f");

    private final SecretKey key = AccessTokens.key(SECRET);

    private static JwtDecoder decoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(AccessTokens.validator());
        return decoder;
    }

    private static AccessTokens tokens(SecretKey key, Clock clock) {
        return new AccessTokens(NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build(), Duration.ofMinutes(15), clock);
    }

    @Test
    void anIssuedTokenFollowsRfc9068AndNamesOnlyTheAccount() {
        AccessTokens.Issued issued = tokens(key, Clock.systemUTC()).issue(ACCOUNT);
        assertThat(issued.expiresIn()).isEqualTo(900);

        Jwt jwt = decoder(key).decode(issued.value());
        assertThat(jwt.getHeaders()).containsEntry("typ", "at+jwt").containsEntry("alg", "HS256");
        assertThat(jwt.getSubject()).isEqualTo(ACCOUNT.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("schemaguard");
        assertThat(jwt.getAudience()).containsExactly("schemaguard-api");
        assertThat(jwt.getClaimAsString("client_id")).isEqualTo("schemaguard-web");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "client_id", "sub", "iat", "exp", "jti");
    }

    @Test
    void anExpiredTokenIsRefused() {
        // Issued 20 minutes ago with a 15-minute lifetime: past the minute of allowed clock skew.
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(20)), ZoneOffset.UTC);
        String token = tokens(key, past).issue(ACCOUNT).value();
        assertThatThrownBy(() -> decoder(key).decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        String forged = tokens(AccessTokens.key("another secret of at least thirty-two characters"), Clock.systemUTC()).issue(ACCOUNT).value();
        assertThatThrownBy(() -> decoder(key).decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void anUnsignedTokenIsRefused() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"at+jwt\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64.encodeToString(("{\"sub\":\"" + ACCOUNT + "\",\"iss\":\"schemaguard\",\"aud\":\"schemaguard-api\"}").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> decoder(key).decode(header + "." + claims + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void aSignedTokenOfAnotherTypeOrAudienceIsRefused() {
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("schemaguard").audience(List.of("schemaguard-api")).claim("client_id", "schemaguard-web")
                .subject(ACCOUNT.toString()).issuedAt(now).expiresAt(now.plusSeconds(60)).id("x").build();
        String plainJwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        assertThatThrownBy(() -> decoder(key).decode(plainJwt)).isInstanceOf(JwtException.class);

        JwtClaimsSet otherAudience = JwtClaimsSet.from(claims).audience(List.of("somewhere-else")).build();
        String misdirected = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("at+jwt").build(), otherAudience))
                .getTokenValue();
        assertThatThrownBy(() -> decoder(key).decode(misdirected)).isInstanceOf(JwtException.class);
    }

    @Test
    void aConfiguredSecretAlwaysGivesTheSameKeyAndAShortOneIsRefused() {
        assertThat(AccessTokens.key(SECRET).getEncoded()).isEqualTo(key.getEncoded()).hasSize(32);
        assertThatThrownBy(() -> AccessTokens.key("too short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void withoutASecretEachStartGetsItsOwnRandomKey() {
        assertThat(AccessTokens.key(null).getEncoded()).hasSize(32).isNotEqualTo(AccessTokens.key("  ").getEncoded());
    }
}
