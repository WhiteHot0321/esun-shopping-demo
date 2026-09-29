package com.esun.shop.monitoring;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private String runWith(String headerValue, MockHttpServletResponse response, AtomicReference<String> mdcSeen)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products/available");
        if (headerValue != null) request.addHeader(CorrelationIdFilter.HEADER, headerValue);
        FilterChain chain = (req, res) -> mdcSeen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);
        return response.getHeader(CorrelationIdFilter.HEADER);
    }

    @Test
    void safeCallerSuppliedIdIsHonouredInMdcAndResponse() throws Exception {
        var seen = new AtomicReference<String>();
        String echoed = runWith("req-2026.09_29-abc", new MockHttpServletResponse(), seen);

        assertThat(echoed).isEqualTo("req-2026.09_29-abc");
        assertThat(seen.get()).isEqualTo("req-2026.09_29-abc");
    }

    @Test
    void missingIdIsGenerated() throws Exception {
        var seen = new AtomicReference<String>();
        String echoed = runWith(null, new MockHttpServletResponse(), seen);

        assertThat(echoed).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(seen.get()).isEqualTo(echoed);
    }

    @Test
    void unsafeOrOversizedIdsAreReplacedSoLogsCannotBeInjected() throws Exception {
        for (String bad : new String[] {"a\r\nfake-log-line", "has space", "<script>", "x".repeat(65), ""}) {
            var seen = new AtomicReference<String>();
            String echoed = runWith(bad, new MockHttpServletResponse(), seen);

            assertThat(echoed).isNotEqualTo(bad);
            assertThat(echoed).matches("[0-9a-f-]{36}");
            assertThat(seen.get()).isEqualTo(echoed);
        }
    }

    @Test
    void mdcIsClearedAfterTheRequestEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        FilterChain failing = (req, res) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotNull();
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(request, new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
