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

import com.jayway.jsonpath.JsonPath;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    private long createMatch(MockHttpSession session, String body) throws Exception {
        var json = mockMvc.perform(post("/api/matches")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
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

    // ── get / update / delete by id ──────────────────────────────────────────

    @Test
    void ownerGetByIdReturnsMatch() throws Exception {
        var session = registerAndLogin("match_get@example.com");
        var id = createMatch(session, VALID_MATCH);

        mockMvc.perform(get("/api/matches/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.opponentName").value("Kowalski"))
                .andExpect(jsonPath("$.setsWon").value(3))
                .andExpect(jsonPath("$.setsLost").value(1))
                .andExpect(jsonPath("$.result").value("WON"))
                .andExpect(jsonPath("$.sets.length()").value(4));
    }

    @Test
    void ownerUpdateChangesFieldsAndDerivedScores() throws Exception {
        var session = registerAndLogin("match_update@example.com");
        var id = createMatch(session, VALID_MATCH);

        mockMvc.perform(put("/api/matches/" + id)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "opponentName": "Nowak",
                                  "matchDate": "2026-05-02",
                                  "notes": "Tough loss",
                                  "sets": [
                                    {"playerScore": 5,  "opponentScore": 11},
                                    {"playerScore": 7,  "opponentScore": 11}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.opponentName").value("Nowak"))
                .andExpect(jsonPath("$.matchDate").value("2026-05-02"))
                .andExpect(jsonPath("$.notes").value("Tough loss"))
                .andExpect(jsonPath("$.setsWon").value(0))
                .andExpect(jsonPath("$.setsLost").value(2))
                .andExpect(jsonPath("$.result").value("LOST"))
                .andExpect(jsonPath("$.sets.length()").value(2));

        mockMvc.perform(get("/api/matches/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opponentName").value("Nowak"))
                .andExpect(jsonPath("$.sets.length()").value(2));
    }

    @Test
    void ownerDeleteReturns204ThenGetReturns404() throws Exception {
        var session = registerAndLogin("match_delete@example.com");
        var id = createMatch(session, VALID_MATCH);

        mockMvc.perform(delete("/api/matches/" + id).with(csrf()).session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/matches/" + id).session(session))
                .andExpect(status().isNotFound());
    }

    // ── set replacement (flush-ordering guard) ───────────────────────────────

    @Test
    void updateShrinksSetsWithOverlappingSetNumbers() throws Exception {
        var session = registerAndLogin("match_setreplace@example.com");
        // VALID_MATCH has 4 sets (set_number 1..4). Replace with 2 sets whose
        // set_number values (1, 2) overlap the originals — the orphan DELETEs
        // must flush before the INSERTs or uq_match_sets_match_set is violated.
        var id = createMatch(session, VALID_MATCH);

        mockMvc.perform(put("/api/matches/" + id)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "opponentName": "Kowalski",
                                  "matchDate": "2026-05-01",
                                  "sets": [
                                    {"playerScore": 11, "opponentScore": 4},
                                    {"playerScore": 11, "opponentScore": 6}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sets.length()").value(2))
                .andExpect(jsonPath("$.setsWon").value(2))
                .andExpect(jsonPath("$.setsLost").value(0))
                .andExpect(jsonPath("$.result").value("WON"))
                .andExpect(jsonPath("$.sets[0].setNumber").value(1))
                .andExpect(jsonPath("$.sets[1].setNumber").value(2));

        mockMvc.perform(get("/api/matches/" + id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sets.length()").value(2));
    }

    // ── ownership hard rule: get / update / delete ───────────────────────────

    @Test
    void crossUserGetReturns404() throws Exception {
        var sessionA = registerAndLogin("match_xget_a@example.com");
        var id = createMatch(sessionA, VALID_MATCH);

        var sessionB = registerAndLogin("match_xget_b@example.com");
        mockMvc.perform(get("/api/matches/" + id).session(sessionB))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossUserUpdateReturns404() throws Exception {
        var sessionA = registerAndLogin("match_xput_a@example.com");
        var id = createMatch(sessionA, VALID_MATCH);

        var sessionB = registerAndLogin("match_xput_b@example.com");
        mockMvc.perform(put("/api/matches/" + id)
                        .with(csrf()).session(sessionB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MATCH))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossUserDeleteReturns404() throws Exception {
        var sessionA = registerAndLogin("match_xdel_a@example.com");
        var id = createMatch(sessionA, VALID_MATCH);

        var sessionB = registerAndLogin("match_xdel_b@example.com");
        mockMvc.perform(delete("/api/matches/" + id).with(csrf()).session(sessionB))
                .andExpect(status().isNotFound());
    }

    // ── anonymous mutation rejected ──────────────────────────────────────────

    @Test
    void anonymousUpdateReturns401() throws Exception {
        mockMvc.perform(put("/api/matches/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MATCH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousDeleteReturns401() throws Exception {
        mockMvc.perform(delete("/api/matches/1").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ── update validation ────────────────────────────────────────────────────

    @Test
    void updateWithBlankOpponentReturns400() throws Exception {
        var session = registerAndLogin("match_updval@example.com");
        var id = createMatch(session, VALID_MATCH);

        mockMvc.perform(put("/api/matches/" + id)
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"opponentName":"","matchDate":"2026-05-01","sets":[{"playerScore":11,"opponentScore":5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.opponentName").exists());
    }
}
