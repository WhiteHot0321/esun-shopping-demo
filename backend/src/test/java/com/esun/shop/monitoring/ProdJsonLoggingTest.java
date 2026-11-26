package com.esun.shop.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LoggingInitializationContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 088 B-7: {@code logback-spring.xml} had no test coverage. The prod profile must emit one JSON object per line
 * (logstash-logback-encoder is still Jackson 2 and must keep working beside Boot 4's Jackson 3).
 *
 * <p>The real file is applied to a private {@link LoggerContext} with the {@code prod} profile active. Booting a Spring
 * context instead would not work in the full suite: Boot skips re-initialising logging while an earlier cached context
 * still owns the JVM's logging system.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProdJsonLoggingTest {

    @Test
    void prodProfileWritesOneJsonObjectPerLineWithMdcCorrelationIdAndStackTrace(CapturedOutput output) throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
            environment.setActiveProfiles("prod");
            // Boot's configurator (the one that understands <springProfile>) is package-private, hence reflection.
            Constructor<?> ctor = Class.forName("org.springframework.boot.logging.logback.SpringBootJoranConfigurator")
                    .getDeclaredConstructor(LoggingInitializationContext.class);
            ctor.setAccessible(true);
            JoranConfigurator configurator = (JoranConfigurator) ctor.newInstance(new LoggingInitializationContext(environment));
            configurator.setContext(context);
            configurator.doConfigure(new ClassPathResource("logback-spring.xml").getURL());

            Logger log = context.getLogger("prod.json.logging.test");
            context.getMDCAdapter().put("correlationId", "cid-088");
            log.info("json-log-probe");
            log.error("json-log-failure", new IllegalStateException("boom-088"));
        } finally {
            context.stop();
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
