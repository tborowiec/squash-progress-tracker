package org.borowiec.squashprogresstracker.match;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MatchNoMisSaveTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LlmClient llmClient;

    // ── Create fixture ────────────────────────────────────────────────────────
    private static final String CREATE_OPPONENT = "Jankowski";
    private static final String CREATE_DATE = "2026-02-15";
    private static final String CREATE_NOTES = "Played at the club, humid conditions";
    private static final int CREATE_S1_P = 11;
    private static final int CREATE_S1_O = 3;
    private static final int CREATE_S2_P = 7;
    private static final int CREATE_S2_O = 11;
    private static final int CREATE_S3_P = 11;
    private static final int CREATE_S3_O = 8;

    private static final String CREATE_BODY =
            """
            {
              "opponentName": "%s",
              "matchDate": "%s",
              "notes": "%s",
              "sets": [
                {"playerScore": %d, "opponentScore": %d},
                {"playerScore": %d, "opponentScore": %d},
                {"playerScore": %d, "opponentScore": %d}
              ]
            }
            """
                    .formatted(
                            CREATE_OPPONENT,
                            CREATE_DATE,
                            CREATE_NOTES,
                            CREATE_S1_P,
                            CREATE_S1_O,
                            CREATE_S2_P,
                            CREATE_S2_O,
                            CREATE_S3_P,
                            CREATE_S3_O);

    // ── Update fixture (2 sets, different opponent/date/notes — exercises set-count change) ──
    private static final String UPDATE_OPPONENT = "Zielinski";
    private static final String UPDATE_DATE = "2026-03-20";
    private static final String UPDATE_NOTES = "Tournament match, good pace";
    private static final int UPDATE_S1_P = 9;
    private static final int UPDATE_S1_O = 11;
    private static final int UPDATE_S2_P = 11;
    private static final int UPDATE_S2_O = 6;

    private static final String UPDATE_BODY =
            """
            {
              "opponentName": "%s",
              "matchDate": "%s",
              "notes": "%s",
              "sets": [
                {"playerScore": %d, "opponentScore": %d},
                {"playerScore": %d, "opponentScore": %d}
              ]
            }
            """
                    .formatted(
                            UPDATE_OPPONENT,
                            UPDATE_DATE,
                            UPDATE_NOTES,
                            UPDATE_S1_P,
                            UPDATE_S1_O,
                            UPDATE_S2_P,
                            UPDATE_S2_O);

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private long createMatch(MockHttpSession session, String body) throws Exception {
        var json = mockMvc.perform(post("/api/matches")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    // ── 2.1 Confirmed == saved (create path) ─────────────────────────────────

    @Test
    void confirmedMatchEqualsSavedOnFreshGet() throws Exception {
        var session = registerAndLogin("nomissave_create@example.com");
        var id = createMatch(session, CREATE_BODY);

        mockMvc.perform(get("/api/matches/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opponentName").value(CREATE_OPPONENT))
                .andExpect(jsonPath("$.matchDate").value(CREATE_DATE))
                .andExpect(jsonPath("$.notes").value(CREATE_NOTES))
                .andExpect(jsonPath("$.sets.length()").value(3))
                .andExpect(jsonPath("$.sets[0].playerScore").value(CREATE_S1_P))
                .andExpect(jsonPath("$.sets[0].opponentScore").value(CREATE_S1_O))
                .andExpect(jsonPath("$.sets[0].setNumber").value(1))
                .andExpect(jsonPath("$.sets[1].playerScore").value(CREATE_S2_P))
                .andExpect(jsonPath("$.sets[1].opponentScore").value(CREATE_S2_O))
                .andExpect(jsonPath("$.sets[1].setNumber").value(2))
                .andExpect(jsonPath("$.sets[2].playerScore").value(CREATE_S3_P))
                .andExpect(jsonPath("$.sets[2].opponentScore").value(CREATE_S3_O))
                .andExpect(jsonPath("$.sets[2].setNumber").value(3));
    }

    // ── 2.2 Confirmed == saved (update path, set count changes 3→2) ──────────

    @Test
    void confirmedUpdateEqualsSavedOnFreshGet() throws Exception {
        var session = registerAndLogin("nomissave_update@example.com");
        var id = createMatch(session, CREATE_BODY);

        mockMvc.perform(put("/api/matches/" + id)
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/matches/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opponentName").value(UPDATE_OPPONENT))
                .andExpect(jsonPath("$.matchDate").value(UPDATE_DATE))
                .andExpect(jsonPath("$.notes").value(UPDATE_NOTES))
                .andExpect(jsonPath("$.sets.length()").value(2))
                .andExpect(jsonPath("$.sets[0].playerScore").value(UPDATE_S1_P))
                .andExpect(jsonPath("$.sets[0].opponentScore").value(UPDATE_S1_O))
                .andExpect(jsonPath("$.sets[0].setNumber").value(1))
                .andExpect(jsonPath("$.sets[1].playerScore").value(UPDATE_S2_P))
                .andExpect(jsonPath("$.sets[1].opponentScore").value(UPDATE_S2_O))
                .andExpect(jsonPath("$.sets[1].setNumber").value(2));
    }

    // ── 2.3 Parse is side-effect-free ────────────────────────────────────────

    @Test
    void parseDoesNotPersistAMatch() throws Exception {
        var session = registerAndLogin("nomissave_parse@example.com");

        when(llmClient.generateStructured(any(), any()))
                .thenReturn(new MatchParseResult(
                        "StubOpponent", "2026-01-01", null, List.of(new MatchParseResult.ParsedSet(11, 5))));

        mockMvc.perform(get("/api/matches").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/matches/parse")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"played Nowak 11-5\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/matches").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
