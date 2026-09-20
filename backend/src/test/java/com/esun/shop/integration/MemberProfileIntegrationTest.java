package com.esun.shop.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MemberProfileIntegrationTest extends AbstractMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void profileRequiresJwtAndReadsAndUpdatesOnlyAuthenticatedMember() throws Exception {
        String email = "profile-" + UUID.randomUUID() + "@example.com";
        String otherEmail = "profile-other-" + UUID.randomUUID() + "@example.com";
        String token = registerAndToken(email);
        registerAndToken(otherEmail);

        mvc.perform(get("/api/member/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/member/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.displayName").isEmpty())
                .andExpect(jsonPath("$.data.phone").isEmpty());

        mvc.perform(put("/api/member/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "displayName", "  王小明  ",
                                "phone", "  0912-345-678  ",
                                "email", otherEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.displayName").value("王小明"))
                .andExpect(jsonPath("$.data.phone").value("0912-345-678"));

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT display_name, phone FROM member WHERE email = ?", email);
        assertThat(stored).containsEntry("display_name", "王小明").containsEntry("phone", "0912-345-678");
        Integer untouched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM member WHERE email = ? AND display_name IS NULL AND phone IS NULL",
                Integer.class, otherEmail);
        assertThat(untouched).isEqualTo(1);
    }

    @Test
    void profileValidationRejectsInvalidPhoneAndBlankValuesClearOptionalFields() throws Exception {
        String email = "profile-validation-" + UUID.randomUUID() + "@example.com";
        String token = registerAndToken(email);

        mvc.perform(put("/api/member/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("displayName", "User", "phone", "call-me"))))
                .andExpect(status().isBadRequest());

        jdbc.update("UPDATE member SET display_name = 'Old', phone = '0900' WHERE email = ?", email);
        mvc.perform(put("/api/member/profile").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("displayName", "  ", "phone", "  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").isEmpty())
                .andExpect(jsonPath("$.data.phone").isEmpty());
        Integer cleared = jdbc.queryForObject(
                "SELECT COUNT(*) FROM member WHERE email = ? AND display_name IS NULL AND phone IS NULL",
                Integer.class, email);
        assertThat(cleared).isEqualTo(1);
    }

    private String registerAndToken(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("token").asText();
    }
}
