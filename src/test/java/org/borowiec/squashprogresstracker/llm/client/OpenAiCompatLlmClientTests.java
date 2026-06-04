package org.borowiec.squashprogresstracker.llm.client;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiCompatLlmClientTests {

    private static final String BASE_URL = "http://llm-test.local";
    private static final String COMPLETIONS_URL = BASE_URL + "/chat/completions";

    record Pong(String word) {}

    private MockRestServiceServer mockServer;
    private OpenAiCompatLlmClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer test-key");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        var props = new LlmClientProperties("test-key", BASE_URL, "test-model", "", Duration.ofSeconds(30));
        client = new OpenAiCompatLlmClient(builder.build(), props, objectMapper);
    }

    @Test
    void generate_happyPath_returnsChoiceContentAndSendsBearerHeader() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(bodyJson(json -> assertThat(json.path("model").asString()).isEqualTo("test-model")))
                .andRespond(withSuccess(completionJson("hello world"), MediaType.APPLICATION_JSON));

        assertThat(client.generate(LlmRequest.ofUser("ping"))).isEqualTo("hello world");
        mockServer.verify();
    }

    @Test
    void generateStructured_sendsJsonSchemaWithStrictTrue_andDeserializes() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andExpect(bodyJson(json -> {
                    assertThat(json.path("model").asString()).isEqualTo("test-model");
                    assertThat(json.path("response_format").path("type").asString()).isEqualTo("json_schema");
                    assertThat(json.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
                }))
                .andRespond(withSuccess(
                        completionJson("""
                                {"word":"pong"}"""),
                        MediaType.APPLICATION_JSON));

        var result = client.generateStructured(LlmRequest.ofUser("ping"), Pong.class);

        assertThat(result.word()).isEqualTo("pong");
        mockServer.verify();
    }

    @Test
    void generate_blankStructuredModel_fallsBackToModel() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andExpect(bodyJson(json -> assertThat(json.path("model").asString()).isEqualTo("test-model")))
                .andRespond(withSuccess(completionJson("{\"word\":\"ok\"}"), MediaType.APPLICATION_JSON));

        var result = client.generateStructured(LlmRequest.ofUser("ping"), Pong.class);
        assertThat(result.word()).isEqualTo("ok");
        mockServer.verify();
    }

    @Test
    void generate_http4xx_throwsLlmExceptionWithStatus() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.generate(LlmRequest.ofUser("test")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).providerStatus()).isEqualTo(401));
    }

    @Test
    void generate_http5xx_throwsLlmExceptionWithStatus() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.generate(LlmRequest.ofUser("test")))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).providerStatus()).isEqualTo(500));
    }

    @Test
    void generate_emptyChoices_throwsLlmException() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate(LlmRequest.ofUser("test")))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void generate_nullContent_throwsLlmException() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":null}}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate(LlmRequest.ofUser("test")))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void generate_ioException_translatedToLlmException() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        // Spring wraps the SocketTimeoutException in ResourceAccessException before our adapter sees it
        assertThatThrownBy(() -> client.generate(LlmRequest.ofUser("test")))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void llmRestClient_beanCreatedWithConfiguredTimeout() {
        var props = new LlmClientProperties("key", BASE_URL, "model", "", Duration.ofSeconds(15));
        assertThatCode(() -> new LlmClientConfig().llmRestClient(props)).doesNotThrowAnyException();
    }

    @Test
    void generateStreaming_happyPath_orderedTokensAndIgnoresEmptyDelta() {
        var sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n" +
                      "data: {\"choices\":[{\"delta\":{\"content\":\" World\"}}]}\n" +
                      "data: {\"choices\":[{\"delta\":{}}]}\n" +
                      "data: [DONE]\n";
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(bodyJson(json -> {
                    assertThat(json.path("model").asString()).isEqualTo("test-model");
                    assertThat(json.path("stream").asBoolean()).isTrue();
                }))
                .andRespond(withSuccess(sseBody, MediaType.TEXT_EVENT_STREAM));

        var tokens = new ArrayList<String>();
        client.generateStreaming(LlmRequest.ofUser("ping"), tokens::add);

        assertThat(tokens).containsExactly("Hello", " World");
        mockServer.verify();
    }

    @Test
    void generateStreaming_http5xx_throwsLlmExceptionWithStatus() {
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.generateStreaming(LlmRequest.ofUser("test"), token -> {}))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).providerStatus()).isEqualTo(500));
    }

    @Test
    void generateStreaming_malformedChunk_throwsLlmException() {
        var sseBody = "data: not-valid-json\n";
        mockServer.expect(requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess(sseBody, MediaType.TEXT_EVENT_STREAM));

        assertThatThrownBy(() -> client.generateStreaming(LlmRequest.ofUser("test"), token -> {}))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void parseSseStream_onTokenFiresInOrder() throws Exception {
        var sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n" +
                      "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n" +
                      "data: {\"choices\":[{\"delta\":{}}]}\n" +
                      "data: [DONE]\n" +
                      "data: {\"choices\":[{\"delta\":{\"content\":\"after-done\"}}]}\n";
        var tokens = new ArrayList<String>();
        OpenAiCompatLlmClient.parseSseStream(
                new BufferedReader(new StringReader(sseBody)),
                tokens::add,
                objectMapper
        );
        assertThat(tokens).containsExactly("A", "B");
    }

    private org.springframework.test.web.client.RequestMatcher bodyJson(Consumer<JsonNode> assertions) {
        return request -> {
            var body = ((MockClientHttpRequest) request).getBodyAsString();
            assertions.accept(objectMapper.readTree(body));
        };
    }

    private String completionJson(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"%s\"}}]}"
                .formatted(content.replace("\"", "\\\"").replace("\n", ""));
    }
}
