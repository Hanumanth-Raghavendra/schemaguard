package io.github.hanumanthraghavendra.schemaguard.api.auth;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may call what. Every analysis, saved analysis and auth endpoint stays public; the account
 * and project endpoints need an access token ({@code Authorization: Bearer}), checked by Spring Security's
 * OAuth 2.0 resource server against this service's own key.
 *
 * <p>No server sessions and no CSRF tokens. Endpoints that act on an account authenticate with
 * the Authorization header, which a browser never adds by itself, so another site cannot forge
 * such a request. The only endpoints that read a cookie are refresh and logout; their cookie is
 * SameSite=Strict, and the CORS check refuses requests from origins not on the list (D-091).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /** Names of the security schemes in the OpenAPI document. */
    public static final String BEARER = "accessToken";
    public static final String REFRESH_COOKIE = "refreshCookie";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JsonMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = problemEntryPoint(json);
        AccessDeniedHandler forbidden = problemAccessDenied(json);
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/account", "/api/v1/account/**", "/api/v1/projects", "/api/v1/projects/**")
                        .authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(server -> server
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    @Bean
    SecretKey accessTokenKey(ApiProperties properties) {
        String secret = properties.auth().jwtSecret();
        if (secret == null || secret.isBlank()) {
            LOG.info("No schemaguard.auth.jwt-secret: access tokens are signed with a key made at startup and stop"
                    + " working on restart; clients then renew them with their refresh token");
        }
        return AccessTokens.key(secret);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey accessTokenKey) {
        return NimbusJwtEncoder.withSecretKey(accessTokenKey).algorithm(MacAlgorithm.HS256).build();
    }

    /** Accepts only HS256 tokens signed with our key that pass RFC 9068's checks with our values. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey accessTokenKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(accessTokenKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(AccessTokens.validator());
        return decoder;
    }

    @Bean
    AccessTokens accessTokens(JwtEncoder jwtEncoder, ApiProperties properties) {
        return new AccessTokens(jwtEncoder, properties.auth().accessTokenLifetime(), Clock.systemUTC());
    }

    /**
     * Browser origins allowed to call {@code /api/**}. Spring Security's CORS filter reads this, so
     * a request from another origin is refused before it reaches any endpoint.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(ApiProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.addAllowedMethod("GET");
        cors.addAllowedMethod("POST");
        cors.addAllowedMethod("PATCH");
        cors.addAllowedMethod("DELETE");
        cors.addAllowedHeader("Content-Type");
        cors.addAllowedHeader("Authorization");
        cors.addAllowedHeader("X-Delete-Token");
        cors.addExposedHeader("Location");
        cors.addExposedHeader("Retry-After");
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /** 401 as a problem document, with the {@code WWW-Authenticate: Bearer} header RFC 6750 asks for. */
    private static AuthenticationEntryPoint problemEntryPoint(JsonMapper json) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception);
            String detail = hasBearerToken(request)
                    ? "The access token has expired or is not valid. Renew the session or log in again."
                    : "Log in to use this endpoint: send an access token as Authorization: Bearer.";
            write(json, request, response, HttpStatus.UNAUTHORIZED, detail);
        };
    }

    private static AccessDeniedHandler problemAccessDenied(JsonMapper json) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            bearer.handle(request, response, exception);
            write(json, request, response, HttpStatus.FORBIDDEN, "This account may not do that.");
        };
    }

    private static boolean hasBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static void write(JsonMapper json, HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", "SG-API-" + status.value());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
