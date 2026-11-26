package com.esun.shop.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 088 B-7: {@code logback-spring.xml} had no test coverage. The prod profile must emit one JSON object per line
 * (logstash-logback-encoder is still Jackson 2 and must keep working beside Boot 4's Jackson 3).
 * Only a tiny context is started so the real logback-spring.xml is applied with the {@code prod} profile and no
 * database or Redis is needed.
 */
@SpringBootTest(classes = ProdJsonLoggingTest.NoBeans.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.profiles.active=prod")
@ExtendWith(OutputCaptureExtension.class)
class ProdJsonLoggingTest {

    private static final Logger LOG = LoggerFactory.getLogger("prod.json.logging.test");

    @Configuration
    static class NoBeans {
    }

    @Test
    void prodProfileWritesOneJsonObjectPerLineWithMdcCorrelationIdAndStackTrace(CapturedOutput output) {
        MDC.put("correlationId", "cid-088");
        try {
            LOG.info("json-log-probe");
            LOG.error("json-log-failure", new IllegalStateException("boom-088"));
        } finally {
            MDC.remove("correlationId");
        }

        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode info = jsonLineContaining(output, mapper, "json-log-probe");
        assertThat(info.path("message").asString()).isEqualTo("json-log-probe");
        assertThat(info.path("level").asString()).isEqualTo("INFO");
        assertThat(info.path("logger_name").asString()).isEqualTo("prod.json.logging.test");
        assertThat(info.path("correlationId").asString()).isEqualTo("cid-088");
        assertThat(info.has("@timestamp")).isTrue();

        JsonNode error = jsonLineContaining(output, mapper, "json-log-failure");
        assertThat(error.path("level").asString()).isEqualTo("ERROR");
        assertThat(error.path("stack_trace").asString()).contains("IllegalStateException").contains("boom-088");
    }

    private static JsonNode jsonLineContaining(CapturedOutput output, JsonMapper mapper, String marker) {
        String line = Arrays.stream(output.getAll().split("\\R"))
                .filter(l -> l.contains("\"" + marker + "\"") || l.contains(":\"" + marker))
                .findFirst().orElseThrow(() -> new AssertionError("no JSON log line for " + marker + " in:\n" + output.getAll()));
        assertThat(line).startsWith("{").endsWith("}");
        return mapper.readTree(line);
    }
}
