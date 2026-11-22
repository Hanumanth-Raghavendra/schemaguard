package io.github.hanumanthraghavendra.schemaguard.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hanumanthraghavendra.schemaguard.api.config.ApiProperties;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.Database;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.PersistenceUnavailableException;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.Secrets;
import io.github.hanumanthraghavendra.schemaguard.api.persistence.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Sessions and lockouts over time, on a real PostgreSQL database, with a clock the test moves. */
@EnabledIfEnvironmentVariable(named = TestDatabase.SWITCH, matches = "1")
class AccountServiceTest {

    private static final String PASSWORD = "correct horse battery staple";

    private static TestDatabase database;
    private static DriverManagerDataSource dataSource;
    private static AccountStore store;
    private static final PasswordHasher HASHER = new PasswordHasher();

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-22T10:00:00Z"));
    private final ApiProperties.Auth settings =
            new ApiProperties.Auth(null, Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofDays(90), Duration.ofSeconds(30));
    private AccountService service;

    @BeforeAll
    static void createDatabase() {
        database = TestDatabase.create("sg_accounts_test");
        dataSource = new DriverManagerDataSource(database.url(), database.user(), database.password());
        store = new AccountStore(new Database(dataSource));
    }

    @BeforeEach
    void createService() {
        service = service(store, clock);
    }

    @AfterAll
    static void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    private AccountService service(AccountStore accounts, Clock time) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(accounts == null ? Map.of() : Map.of("accountStore", accounts));
        AccessTokens tokens = new AccessTokens(
                NimbusJwtEncoder.withSecretKey(AccessTokens.key(null)).algorithm(MacAlgorithm.HS256).build(), Duration.ofMinutes(15), time);
        return new AccountService(beans.getBeanProvider(AccountStore.class), new PasswordPolicy(), HASHER, tokens, settings, time);
    }

    private static String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private void expectEnded(String refreshToken) {
        assertThatThrownBy(() -> service.refresh(refreshToken))
                .isInstanceOf(SessionEndedException.class)
                .hasMessage("Your session has ended; log in again.");
    }

    @Test
    void eachRefreshTokenWorksOnceAndGivesTheNextOne() {
        AccountService.Grant first = service.signUp(email(), PASSWORD, null);
        assertThat(first.refreshMaxAge()).isEqualTo(Duration.ofDays(30));

        clock.advance(Duration.ofMinutes(20));
        AccountService.Grant second = service.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.account().id()).isEqualTo(first.account().id());

        clock.advance(Duration.ofMinutes(20));
        AccountService.Grant third = service.refresh(second.refreshToken());
        assertThat(third.refreshToken()).isNotEqualTo(second.refreshToken());
    }

    @Test
    void aTokenUsedAgainAfterTheGracePeriodEndsTheWholeSession() {
        AccountService.Grant login = service.signUp(email(), PASSWORD, null);
        AccountService.Grant renewed = service.refresh(login.refreshToken());

        // A copy of the first token, stolen before it was exchanged, comes back a minute later.
        clock.advance(Duration.ofMinutes(1));
        expectEnded(login.refreshToken());
        // The owner's current token dies with the session: whoever holds it must log in again.
        expectEnded(renewed.refreshToken());
    }

    @Test
    void twoTabsRefreshingAtOnceKeepTheSession() {
        AccountService.Grant login = service.signUp(email(), PASSWORD, null);
        AccountService.Grant firstTab = service.refresh(login.refreshToken());
        clock.advance(Duration.ofSeconds(2));
        AccountService.Grant secondTab = service.refresh(login.refreshToken());

        assertThat(secondTab.refreshToken()).isNotEqualTo(firstTab.refreshToken());
        clock.advance(Duration.ofMinutes(5));
        assertThat(service.refresh(firstTab.refreshToken()).account().id()).isEqualTo(login.account().id());
        assertThat(service.refresh(secondTab.refreshToken()).account().id()).isEqualTo(login.account().id());
    }

    @Test
    void anUnusedRefreshTokenExpiresAfterThirtyDays() {
        AccountService.Grant login = service.signUp(email(), PASSWORD, null);
        clock.advance(Duration.ofDays(30));
        expectEnded(login.refreshToken());
    }

    @Test
    void anActiveSessionStillEndsNinetyDaysAfterTheLogin() {
        AccountService.Grant grant = service.signUp(email(), PASSWORD, null);
        for (int day = 25; day < 90; day += 25) {
            clock.advance(Duration.ofDays(25));
            grant = service.refresh(grant.refreshToken());
        }
        // Day 75: the new token may not outlive the session, which ends on day 90.
        assertThat(grant.refreshMaxAge()).isEqualTo(Duration.ofDays(15));
        clock.advance(Duration.ofDays(15));
        expectEnded(grant.refreshToken());
    }

    @Test
    void loggingOutEndsTheSessionAndOnlyThatOne() {
        String email = email();
        AccountService.Grant laptop = service.signUp(email, PASSWORD, null);
        AccountService.Grant phone = service.logIn(email, PASSWORD, null);

        service.logOut(laptop.refreshToken());
        expectEnded(laptop.refreshToken());
        assertThat(service.refresh(phone.refreshToken()).account().email()).isEqualTo(email);
        service.logOut(null);
        service.logOut("not a token we ever issued");
    }

    @Test
    void loggingInAgainInTheSameBrowserEndsItsOldSession() {
        String email = email();
        AccountService.Grant old = service.signUp(email, PASSWORD, null);
        service.logIn(email, PASSWORD, old.refreshToken());
        expectEnded(old.refreshToken());
    }

    @Test
    void unknownMissingAndOversizedTokensAreRefusedTheSameWay() {
        expectEnded(null);
        expectEnded("");
        expectEnded(Secrets.newToken());
        expectEnded("x".repeat(129));
    }

    @Test
    void fiveFailuresInARowLockTheAccountForAMinuteThenLongerEachTime() {
        String email = email();
        service.signUp(email, PASSWORD, null);
        for (int i = 1; i < AccountService.MAX_FAILURES; i++) {
            assertThatThrownBy(() -> service.logIn(email, "wrong password", null))
                    .isInstanceOf(LoginFailedException.class)
                    .hasMessage("The email or password is wrong.");
        }
        assertThatThrownBy(() -> service.logIn(email, "wrong password", null))
                .isInstanceOf(AccountLockedException.class)
                .hasMessage("Too many failed logins for this account. Try again in 1 minute.");
        // While locked, even the right password is refused, without being checked.
        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> service.logIn(email, PASSWORD, null))
                .isInstanceOfSatisfying(AccountLockedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(30));

        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> service.logIn(email, "wrong again", null))
                .hasMessage("Too many failed logins for this account. Try again in 2 minutes.");

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.logIn(email, PASSWORD, null).account().email()).isEqualTo(email);
        // Success forgets the failures: the next mistake is the first again.
        assertThatThrownBy(() -> service.logIn(email, "wrong password", null)).isInstanceOf(LoginFailedException.class);
    }

    @Test
    void failuresAnHourApartDoNotAddUp() {
        String email = email();
        service.signUp(email, PASSWORD, null);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> service.logIn(email, "wrong password", null)).isInstanceOf(LoginFailedException.class);
            clock.advance(Duration.ofMinutes(61));
        }
    }

    @Test
    void locksGrowFromOneMinuteToAQuarterOfAnHour() {
        assertThat(AccountService.lockFor(5)).isEqualTo(Duration.ofMinutes(1));
        assertThat(AccountService.lockFor(6)).isEqualTo(Duration.ofMinutes(2));
        assertThat(AccountService.lockFor(8)).isEqualTo(Duration.ofMinutes(8));
        assertThat(AccountService.lockFor(9)).isEqualTo(Duration.ofMinutes(15));
        assertThat(AccountService.lockFor(1_000)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void anEmailWithoutAnAccountFailsLikeAWrongPassword() {
        assertThatThrownBy(() -> service.logIn(email(), PASSWORD, null))
                .isInstanceOf(LoginFailedException.class)
                .hasMessage("The email or password is wrong.");
    }

    @Test
    void theEmailIsMatchedWhateverItsCase() {
        String email = email();
        service.signUp(email, PASSWORD, null);
        assertThat(service.logIn("  " + email.toUpperCase() + " ", PASSWORD, null).account().email()).isEqualTo(email);
        assertThatThrownBy(() -> service.signUp(email.toUpperCase(), PASSWORD, null))
                .isInstanceOf(EmailTakenException.class)
                .hasMessage("An account with this email already exists. Log in instead.");
    }

    @Test
    void anOldHashIsReplacedAtTheNextLogin() {
        String email = email();
        AccountService.Grant grant = service.signUp(email, PASSWORD, null);
        String weak = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        store.updatePasswordHash(grant.account().id(), weak);

        service.logIn(email, PASSWORD, null);
        String stored = JdbcClient.create(dataSource).sql("SELECT password_hash FROM account WHERE id = :id")
                .param("id", grant.account().id()).query(String.class).single();
        assertThat(stored).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    void deletingNeedsThePasswordAndTakesEverySessionWithIt() {
        String email = email();
        AccountService.Grant grant = service.signUp(email, PASSWORD, null);
        UUID id = grant.account().id();
        assertThatThrownBy(() -> service.delete(id, "wrong password"))
                .isInstanceOf(WrongPasswordException.class)
                .hasMessage("The password is wrong.");

        service.delete(id, PASSWORD);
        expectEnded(grant.refreshToken());
        assertThatThrownBy(() -> service.account(id)).isInstanceOf(SessionEndedException.class).hasMessage("This account no longer exists.");
        assertThatThrownBy(() -> service.logIn(email, PASSWORD, null)).isInstanceOf(LoginFailedException.class);
        assertThat(JdbcClient.create(dataSource).sql("SELECT count(*) FROM refresh_token WHERE account_id = :id")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void expiredTokensAreDeletedByTheRetentionJob() {
        AccountService.Grant grant = service.signUp(email(), PASSWORD, null);
        String hash = Secrets.hash(grant.refreshToken());
        assertThat(store.deleteExpiredTokens(clock.instant())).isZero();
        assertThat(store.findToken(hash)).isPresent();
        assertThat(store.deleteExpiredTokens(clock.instant().plus(Duration.ofDays(31)))).isPositive();
        assertThat(store.findToken(hash)).isEmpty();
    }

    @Test
    void withoutADatabaseAccountsAreSwitchedOff() {
        AccountService off = service(null, clock);
        assertThatThrownBy(() -> off.signUp(email(), PASSWORD, null))
                .isInstanceOf(PersistenceUnavailableException.class)
                .hasMessage("Accounts are switched off on this server.");
        assertThatThrownBy(() -> off.refresh(Secrets.newToken())).isInstanceOf(PersistenceUnavailableException.class);
        off.logOut(Secrets.newToken());
    }

    /** A clock the test moves forward by hand. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
