package io.github.hanumanthraghavendra.schemaguard.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void aNeonConnectionStringBecomesAJdbcUrlWithSeparateCredentials() {
        DatabaseUrl url =
                DatabaseUrl.parse(
                        "postgresql://app_owner:s3cr%40t@ep-cool-name-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require",
                        null,
                        null);
        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://ep-cool-name-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require");
        assertThat(url.username()).contains("app_owner");
        assertThat(url.password()).contains("s3cr@t");
    }

    @Test
    void portsAndTheShortSchemeAreKept() {
        DatabaseUrl url = DatabaseUrl.parse("postgres://u:p@db.example:6543/app", null, null);
        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:6543/app");
        assertThat(DatabaseUrl.parse("postgres://db.example", "u", "p").jdbcUrl()).isEqualTo("jdbc:postgresql://db.example/");
    }

    @Test
    void separateCredentialsFillWhatTheStringLeavesOut() {
        DatabaseUrl url = DatabaseUrl.parse("postgresql://db.example/app?sslmode=require", "reader", "pw");
        assertThat(url.username()).contains("reader");
        assertThat(url.password()).contains("pw");
        DatabaseUrl onlyUser = DatabaseUrl.parse("postgresql://owner@db.example/app", "reader", "pw");
        assertThat(onlyUser.username()).contains("owner");
        assertThat(onlyUser.password()).contains("pw");
        assertThat(DatabaseUrl.parse("postgresql://:x@db.example/app", "reader", null).username()).contains("reader");
    }

    @Test
    void aJdbcUrlIsTakenAsItIs() {
        DatabaseUrl url = DatabaseUrl.parse(" jdbc:postgresql://localhost:5432/app ", "u", " ");
        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/app");
        assertThat(url.username()).contains("u");
        assertThat(url.password()).isEqualTo(Optional.empty());
    }

    @Test
    void anythingElseIsRefusedWithoutRepeatingTheSecret() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseUrl.parse("mysql://u:topsecret@h/db", null, null))
                .withMessageNotContaining("topsecret");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabaseUrl.parse("postgresql://u:topsecret@ bad host/db", null, null))
                .withMessageNotContaining("topsecret");
        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse("postgresql:///db", null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse(null, null, null));
    }

    @Test
    void theTextFormNeverShowsThePassword() {
        assertThat(DatabaseUrl.parse("postgresql://u:topsecret@h/db", null, null).toString())
                .doesNotContain("topsecret")
                .contains("user=u");
    }
    @Test
    void neonsPooledHostLeadsToItsDirectHost() {
        // Neon's two strings differ only by -pooler after the endpoint ID (D-118).
        DatabaseUrl pooled =
                DatabaseUrl.parse(
                        "postgresql://app:s3cret@ep-cool-darkness-a1b2c3d4-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require",
                        null,
                        null);
        DatabaseUrl direct = pooled.withoutNeonPooler().orElseThrow();
        assertThat(direct.jdbcUrl())
                .isEqualTo("jdbc:postgresql://ep-cool-darkness-a1b2c3d4.ap-southeast-1.aws.neon.tech/neondb?sslmode=require");
        assertThat(direct.username()).contains("app");
        assertThat(direct.password()).contains("s3cret");

        assertThat(DatabaseUrl.parse("jdbc:postgresql://EP-X-123-POOLER.us-east-2.aws.neon.tech:5432?sslmode=require", "u", "p")
                        .withoutNeonPooler()
                        .map(DatabaseUrl::jdbcUrl))
                .contains("jdbc:postgresql://EP-X-123.us-east-2.aws.neon.tech:5432?sslmode=require");
    }

    @Test
    void anyOtherUrlHasNoDirectHostOfItsOwn() {
        for (String url : new String[] {
            "postgresql://u:p@ep-cool-darkness-a1b2c3d4.ap-southeast-1.aws.neon.tech/neondb",
            "postgresql://u:p@db-pooler.example.com/app",
            "postgresql://u:p@ep-x-pooler.example.com/app",
            "postgresql://u:p@pooler.neon.tech/app",
            "postgresql://u:p@my-pooler.us-east-2.aws.neon.tech/app",
            "jdbc:postgresql://ep-a-pooler.x.neon.tech,ep-b-pooler.x.neon.tech/db",
            "jdbc:postgresql://[::1]:5432/db",
            "jdbc:postgresql:localdb",
            "postgresql://u:p@localhost/app"
        }) {
            assertThat(DatabaseUrl.parse(url, null, null).withoutNeonPooler()).as(url).isEmpty();
        }
    }
}
