package com.esun.shop.docs;

import com.esun.shop.controller.SupportController;
import com.esun.shop.security.JwtAuthFilter;
import com.esun.shop.security.JwtService;
import com.esun.shop.service.SupportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With API_DOCS_ENABLED=false springdoc registers nothing, so the filter-exempt docs paths answer 404 rather than
 * exposing a spec: the switch the production deployment note relies on.
 */
@WebMvcTest(controllers = SupportController.class)
@Import({JwtAuthFilter.class, JwtService.class})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-key-at-least-32-bytes-long",
        "jwt.expiration-ms=3600000",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
class OpenApiDocsDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SupportService supportService;

    @Test
    void disabledDocsAre404NotServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
