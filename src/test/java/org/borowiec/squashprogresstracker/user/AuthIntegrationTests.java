package org.borowiec.squashprogresstracker.user;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    MockMvc mockMvc;

    // ── register ────────────────────────────────────────────────────────────

    @Test
    void registerReturns201AndUserBody() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"alice@example.com","password":"password1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerDuplicateEmailReturns409() throws Exception {
        var body = """
                {"email":"bob@example.com","password":"password1"}
                """;
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void registerWithInvalidEmailReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"not-an-email","password":"password1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void registerWithShortPasswordReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"carol@example.com","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    void loginGoodCredentialsReturns200AndSession() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"dave@example.com","password":"password1"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"dave@example.com","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dave@example.com"));

        // session establishment is verified implicitly by meWithSession and logoutThenMe tests
    }

    @Test
    void loginBadCredentialsReturns401() throws Exception {
        mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"nobody@example.com","password":"wrongpass"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── me ───────────────────────────────────────────────────────────────────

    @Test
    void meWithSessionReturns200AndCorrectIdentity() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"eve@example.com","password":"password1"}
                                """))
                .andExpect(status().isCreated());

        var session = (MockHttpSession) mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"eve@example.com","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("eve@example.com"));
    }

    @Test
    void meWithoutSessionReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // ── logout ───────────────────────────────────────────────────────────────

    @Test
    void logoutThenMeReturns401() throws Exception {
        mockMvc.perform(
                        post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"frank@example.com","password":"password1"}
                                """))
                .andExpect(status().isCreated());

        var session = (MockHttpSession) mockMvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"frank@example.com","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    // ── locale ───────────────────────────────────────────────────────────────

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

    @Test
    void updateLocale_persistsAndReturnsNewLocale() throws Exception {
        var session = registerAndLogin("locale-user@example.com");

        mockMvc.perform(put("/api/auth/me/locale")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"pl\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("pl"));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("pl"));
    }

    @Test
    void updateLocale_invalidTagReturns400() throws Exception {
        var session = registerAndLogin("locale-invalid@example.com");

        mockMvc.perform(put("/api/auth/me/locale")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateLocale_doesNotAffectOtherUsers() throws Exception {
        var sessionA = registerAndLogin("locale-a@example.com");
        var sessionB = registerAndLogin("locale-b@example.com");

        mockMvc.perform(put("/api/auth/me/locale")
                        .with(csrf())
                        .session(sessionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"pl\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"));
    }
}
