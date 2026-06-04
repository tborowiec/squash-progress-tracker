package org.borowiec.squashprogresstracker.match;

import org.borowiec.squashprogresstracker.llm.client.LlmClient;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MatchParseApiIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LlmClient llmClient;

    // ── auth ────────────────────────────────────────────────────────────────

    @Test
    void parse_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/matches/parse")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"beat Kowalski 3:1\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── validation ─────────────────────────────────────────────────────────

    @Test
    void parse_blankText_returns400() throws Exception {
        var session = registerAndLogin("parse_blank@example.com");
        mockMvc.perform(post("/api/matches/parse")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.text").exists());
    }

    @Test
    void parse_missingTextField_returns400() throws Exception {
        var session = registerAndLogin("parse_missing@example.com");
        mockMvc.perform(post("/api/matches/parse")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── happy path ─────────────────────────────────────────────────────────

    @Test
    void parse_validRequest_returns200WithStructuredResult() throws Exception {
        var stubResult = new MatchParseResult(
                "Kowalski", "2026-05-05", "struggled in the second set",
                List.of(
                        new MatchParseResult.ParsedSet(11, 5),
                        new MatchParseResult.ParsedSet(6, 11),
                        new MatchParseResult.ParsedSet(11, 2),
                        new MatchParseResult.ParsedSet(11, 1)
                )
        );
        when(llmClient.generateStructured(any(), eq(MatchParseResult.class))).thenReturn(stubResult);

        var session = registerAndLogin("parse_happy@example.com");
        mockMvc.perform(post("/api/matches/parse")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"beat Kowalski 3:1 (11:5, 6:11, 11:2, 11:1) on May 5th\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opponentName").value("Kowalski"))
                .andExpect(jsonPath("$.matchDate").value("2026-05-05"))
                .andExpect(jsonPath("$.sets.length()").value(4));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private MockHttpSession registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated());
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login")
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
