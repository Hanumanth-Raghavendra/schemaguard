package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Without a database the service runs as before; only the permalink endpoints say they are off. */
@SpringBootTest
@AutoConfigureMockMvc
class PermalinksOffTest {

    private static final String BODY = "{\"before\":\"CREATE TABLE t (a int);\",\"after\":\"CREATE TABLE t (a int, b int);\"}";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void savingAndOpeningAnswer503AndNothingConnectsToADatabase() {
        MvcTestResult save = mvc.post().uri("/api/v1/analyses/report").contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(save.getResponse().getStatus()).isEqualTo(503);
        assertThat(body(save)).contains("\"code\":\"SG-API-503\"", "switched off");

        assertThat(mvc.get().uri("/api/v1/analyses/0123456789abcdefABCDEF").exchange().getResponse().getStatus()).isEqualTo(503);
        assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
    }

    @Test
    void anIdOfTheWrongShapeIsNotFoundWithoutAskingAnyone() {
        assertThat(mvc.get().uri("/api/v1/analyses/not-an-id").exchange().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.delete().uri("/api/v1/analyses/x").exchange().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theStatelessEndpointsAreUnaffected() {
        MvcTestResult report = mvc.post().uri("/api/v1/report").contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(report.getResponse().getStatus()).isEqualTo(200);
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
