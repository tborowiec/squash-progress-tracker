package org.borowiec.squashprogresstracker.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MatchOwnershipBoundaryTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private MockHttpSession sessionB;
    private long matchAId;

    private static final String MINIMAL_VALID_MATCH =
            """
            {
              "opponentName": "Opponent",
              "matchDate": "2026-01-01",
              "sets": [{"playerScore": 11, "opponentScore": 5}]
            }
            """;

    @BeforeAll
    void setUp() throws Exception {
        var sessionA = registerAndLogin("boundary_a@example.com");
        matchAId = createMatch(sessionA, MINIMAL_VALID_MATCH);
        sessionB = registerAndLogin("boundary_b@example.com");
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

    // ── by-id route table ─────────────────────────────────────────────────────
    // Add a row here when a new by-id route is introduced; both sweeps pick it up.

    static Stream<Arguments> byIdRoutes() {
        return Stream.of(Arguments.of(HttpMethod.GET), Arguments.of(HttpMethod.PUT), Arguments.of(HttpMethod.DELETE));
    }

    @Test
    void byIdRoutesTableCoversAllRegisteredByIdEndpoints() {
        var covered = byIdRoutes().map(a -> (HttpMethod) a.get()[0]).collect(Collectors.toSet());

        var registered = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> MatchController.class.equals(e.getValue().getBeanType()))
                .filter(e -> {
                    var patterns = e.getKey().getPathPatternsCondition();
                    return patterns != null
                            && patterns.getPatterns().stream()
                                    .anyMatch(p -> p.getPatternString().matches(".*/\\{[^/]+\\}"));
                })
                .flatMap(e -> e.getKey().getMethodsCondition().getMethods().stream())
                .map(m -> HttpMethod.valueOf(m.name()))
                .collect(Collectors.toSet());

        assertThat(covered)
                .as("byIdRoutes() must cover exactly the by-id routes registered on MatchController")
                .containsExactlyInAnyOrderElementsOf(registered);
    }

    @ParameterizedTest(name = "{0} /api/matches/:id -> 404 for non-owner")
    @MethodSource("byIdRoutes")
    void foreignIdReturns404(HttpMethod method) throws Exception {
        var req =
                switch (method.name()) {
                    case "GET" -> get("/api/matches/" + matchAId).session(sessionB);
                    case "PUT" ->
                        put("/api/matches/" + matchAId)
                                .with(csrf())
                                .session(sessionB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MINIMAL_VALID_MATCH);
                    case "DELETE" ->
                        delete("/api/matches/" + matchAId).with(csrf()).session(sessionB);
                    default -> throw new UnsupportedOperationException("Add a case for: " + method);
                };
        mockMvc.perform(req).andExpect(status().isNotFound());
    }

    // ── anonymous → 401 sweep ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} /api/matches/:id -> 401 for anonymous caller")
    @MethodSource("byIdRoutes")
    void anonymousReturns401(HttpMethod method) throws Exception {
        var req =
                switch (method.name()) {
                    case "GET" -> get("/api/matches/1");
                    case "PUT" ->
                        put("/api/matches/1")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MINIMAL_VALID_MATCH);
                    case "DELETE" -> delete("/api/matches/1").with(csrf());
                    default -> throw new UnsupportedOperationException("Add a case for: " + method);
                };
        mockMvc.perform(req).andExpect(status().isUnauthorized());
    }
}
