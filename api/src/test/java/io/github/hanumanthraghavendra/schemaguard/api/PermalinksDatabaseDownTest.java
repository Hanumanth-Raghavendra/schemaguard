package io.github.hanumanthraghavendra.schemaguard.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A database that cannot be reached must cost the permalinks a 503 and nothing else: the
 * service starts, stays healthy and keeps analysing. Port 1 refuses every connection.
 */
@SpringBootTest(
        properties = {
            "schemaguard.persistence.url=postgresql://nobody:topsecret@127.0.0.1:1/none",
            "schemaguard.persistence.connect-timeout=300ms"
        })
@AutoConfigureMockMvc
class PermalinksDatabaseDownTest {

    private static final String BODY = "{\"before\":\"CREATE TABLE t (a int);\",\"after\":\"CREATE TABLE t (a int, b int);\"}";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void savingAnswers503WithoutLeakingTheConnectionString() {
        MvcTestResult save = mvc.post().uri("/api/v1/analyses/diff").contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(save.getResponse().getStatus()).isEqualTo(503);
        String body = new String(save.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("unavailable right now").doesNotContain("topsecret").doesNotContain("127.0.0.1");
    }

    @Test
    void theRestOfTheServiceKeepsWorking() {
        assertThat(mvc.post().uri("/api/v1/diff").contentType(MediaType.APPLICATION_JSON).content(BODY).exchange().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.get().uri("/actuator/health").exchange().getResponse().getStatus()).isEqualTo(200);
    }
}
