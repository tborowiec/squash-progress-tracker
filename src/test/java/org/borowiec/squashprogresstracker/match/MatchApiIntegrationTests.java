package org.borowiec.squashprogresstracker.match;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MatchApiIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    MockMvc mockMvc;

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

    private static final String VALID_MATCH = """
            {
              "opponentName": "Kowalski",
              "matchDate": "2026-05-01",
              "notes": "Good game",
              "sets": [
                {"playerScore": 11, "opponentScore": 5},
                {"playerScore": 11, "opponentScore": 7},
                {"playerScore": 8,  "opponentScore": 11},
                {"playerScore": 11, "opponentScore": 9}
              ]
            }
            """;

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void createMatchReturns201AndDerivedScores() throws Exception {
        var session = registerAndLogin("match_create@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MATCH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.opponentName").value("Kowalski"))
                .andExpect(jsonPath("$.setsWon").value(3))
                .andExpect(jsonPath("$.setsLost").value(1))
                .andExpect(jsonPath("$.result").value("WON"))
                .andExpect(jsonPath("$.sets.length()").value(4));
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    void blankOpponentNameReturns400() throws Exception {
        var session = registerAndLogin("match_val1@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"","matchDate":"2026-05-01","sets":[{"playerScore":11,"opponentScore":5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.opponentName").exists());
    }

    @Test
    void futureMatchDateReturns400() throws Exception {
        var session = registerAndLogin("match_val2@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"X","matchDate":"2099-01-01","sets":[{"playerScore":11,"opponentScore":5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.matchDate").exists());
    }

    @Test
    void emptySetsReturns400() throws Exception {
        var session = registerAndLogin("match_val3@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"X","matchDate":"2026-05-01","sets":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sets").exists());
    }

    @Test
    void sixSetsReturns400() throws Exception {
        var session = registerAndLogin("match_val4@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"X","matchDate":"2026-05-01","sets":[
                                  {"playerScore":11,"opponentScore":5},
                                  {"playerScore":11,"opponentScore":5},
                                  {"playerScore":11,"opponentScore":5},
                                  {"playerScore":11,"opponentScore":5},
                                  {"playerScore":11,"opponentScore":5},
                                  {"playerScore":11,"opponentScore":5}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sets").exists());
    }

    @Test
    void negativeScoreReturns400() throws Exception {
        var session = registerAndLogin("match_val5@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"X","matchDate":"2026-05-01","sets":[{"playerScore":-1,"opponentScore":5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['sets[0].playerScore']").exists());
    }

    // ── ownership (hard rule) ────────────────────────────────────────────────

    @Test
    void playerBCannotSeePlayerAsMatches() throws Exception {
        var sessionA = registerAndLogin("match_owner_a@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MATCH))
                .andExpect(status().isCreated());

        var sessionB = registerAndLogin("match_owner_b@example.com");
        mockMvc.perform(get("/api/matches").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/matches").session(sessionB).param("opponent", "Kowalski"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anonymousGetMatchesReturns401() throws Exception {
        mockMvc.perform(get("/api/matches"))
                .andExpect(status().isUnauthorized());
    }

    // ── opponents ────────────────────────────────────────────────────────────

    @Test
    void opponentsCollapsesCase() throws Exception {
        var session = registerAndLogin("match_opp@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"Kowalski","matchDate":"2026-04-01","sets":[{"playerScore":11,"opponentScore":5}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"kowalski","matchDate":"2026-04-02","sets":[{"playerScore":5,"opponentScore":11}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/matches/opponents").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void opponentsReturnsOnlyCallerOwned() throws Exception {
        var sessionA = registerAndLogin("match_opp_a@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"Smith","matchDate":"2026-04-01","sets":[{"playerScore":11,"opponentScore":5}]}
                                """))
                .andExpect(status().isCreated());

        var sessionB = registerAndLogin("match_opp_b@example.com");
        mockMvc.perform(get("/api/matches/opponents").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── list ordering ────────────────────────────────────────────────────────

    @Test
    void listReturnsNewestFirst() throws Exception {
        var session = registerAndLogin("match_order@example.com");
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"Alpha","matchDate":"2026-03-01","sets":[{"playerScore":11,"opponentScore":5}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"Beta","matchDate":"2026-04-01","sets":[{"playerScore":5,"opponentScore":11}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/matches").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].opponentName").value("Beta"))
                .andExpect(jsonPath("$[1].opponentName").value("Alpha"));
    }
}
