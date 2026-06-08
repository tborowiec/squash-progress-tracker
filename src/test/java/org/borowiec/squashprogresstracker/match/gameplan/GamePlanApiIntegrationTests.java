package org.borowiec.squashprogresstracker.match.gameplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.borowiec.squashprogresstracker.llm.AiDisclaimer;
import org.borowiec.squashprogresstracker.llm.client.LlmClient;
import org.borowiec.squashprogresstracker.llm.client.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GamePlanApiIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LlmClient llmClient;

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void stream_happyPath_emitsMetaTokensDone() throws Exception {
        stubTokens("Hello ", "World");
        var session = registerAndLogin("gp_happy@example.com");
        logMatch(session, "Kowalski");
        logMatch(session, "Kowalski");
        logMatch(session, "Kowalski");

        var result = mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "Kowalski")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        var body = awaitStreamBody(result);
        assertThat(body).contains("event:meta");
        assertThat(body).contains("\"disclaimer\"");
        assertThat(body).contains("event:token");
        assertThat(body).contains("Hello ");
        assertThat(body).contains("event:done");
        assertThat(result.getResponse().getContentType()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void stream_lowDataFlag_trueForFewerThan3Matches() throws Exception {
        stubTokens("plan");
        var session = registerAndLogin("gp_lowdata@example.com");
        logMatch(session, "Kowalski");

        var result = mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "Kowalski")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(awaitStreamBody(result)).contains("\"lowData\":true");
    }

    @Test
    void stream_lowDataFlag_falseForAtLeast3Matches() throws Exception {
        stubTokens("plan");
        var session = registerAndLogin("gp_nodataissue@example.com");
        logMatch(session, "Kowalski");
        logMatch(session, "Kowalski");
        logMatch(session, "Kowalski");

        var result = mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "Kowalski")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(awaitStreamBody(result)).contains("\"lowData\":false");
    }

    // ── error paths ────────────────────────────────────────────────────────────

    @Test
    void stream_noMatchHistory_returns404() throws Exception {
        var session = registerAndLogin("gp_404@example.com");

        mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "NonExistent")
                        .session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void stream_llmFailure_emitsInStreamErrorEventNot503() throws Exception {
        doThrow(new LlmException("provider down")).when(llmClient).generateStreaming(any(), any());
        var session = registerAndLogin("gp_llmerr@example.com");
        logMatch(session, "Kowalski");

        var result = mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "Kowalski")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        var body = awaitStreamBody(result);
        assertThat(body).contains("event:error");
        assertThat(body).doesNotContain("event:token");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void stream_llmFailureMidStream_stillDeliveredAdviceDisclaimerBeforeError() throws Exception {
        doThrow(new LlmException("provider down", null, 503)).when(llmClient).generateStreaming(any(), any());
        var session = registerAndLogin("gp_disclaimer@example.com");
        logMatch(session, "Kowalski");

        var result = mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "Kowalski")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        // awaitStreamBody reads with UTF-8 so the em dash in AiDisclaimer.TEXT survives the decode.
        var body = awaitStreamBody(result);

        // disclaimer arrives in the meta event before the error event
        assertThat(body).contains("event:meta");
        assertThat(body).contains(AiDisclaimer.TEXT);
        assertThat(body).contains("event:error");
        assertThat(body).contains("AI service is temporarily unavailable");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        // meta must precede error in the stream
        assertThat(body.indexOf("event:meta")).isLessThan(body.indexOf("event:error"));
    }

    // ── ownership ──────────────────────────────────────────────────────────────

    @Test
    void stream_ownershipBoundary_userBCannotGetUserAsPlan() throws Exception {
        stubTokens("plan");
        var sessionA = registerAndLogin("gp_owner_a@example.com");
        logMatch(sessionA, "Kowalski");

        var sessionB = registerAndLogin("gp_owner_b@example.com");
        mockMvc.perform(get("/api/game-plans/stream")
                        .param("opponent", "Kowalski")
                        .session(sessionB))
                .andExpect(status().isNotFound());
    }

    @Test
    void stream_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/game-plans/stream").param("opponent", "anyone"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Wait for the virtual streaming thread to finish writing the SSE response, then return its
     * body (decoded as UTF-8 so the em dash in {@link AiDisclaimer#TEXT} survives). We must NOT call
     * {@code asyncDispatch} here: that runs on the test thread and races the streaming thread on
     * MockHttpServletResponse's non-thread-safe header map (ConcurrentModificationException). Polling
     * until a terminal event ({@code done}/{@code error}) appears lets the streaming thread own every
     * write.
     */
    private String awaitStreamBody(MvcResult result) throws Exception {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .containsPattern("event:(done|error)"));
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void stubTokens(String... tokens) {
        doAnswer(inv -> {
                    var consumer = (Consumer<String>) inv.getArgument(1);
                    for (var token : tokens) {
                        consumer.accept(token);
                    }
                    return null;
                })
                .when(llmClient)
                .generateStreaming(any(), any());
    }

    private MockHttpSession registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated());
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    private void logMatch(MockHttpSession session, String opponent) throws Exception {
        mockMvc.perform(post("/api/matches")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "opponentName": "%s",
                                  "matchDate": "2026-05-01",
                                  "sets": [{"playerScore": 11, "opponentScore": 7}]
                                }
                                """
                                        .formatted(opponent)))
                .andExpect(status().isCreated());
    }
}
