package org.borowiec.squashprogresstracker.llm.client;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

@Service
public class OpenAiCompatLlmClient implements LlmClient {

    private final RestClient restClient;
    private final LlmClientProperties properties;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;

    public OpenAiCompatLlmClient(
            @Qualifier("llmRestClient") RestClient restClient,
            LlmClientProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.schemaFactory = new JsonSchemaFactory(objectMapper);
    }

    @Override
    public String generate(LlmRequest request) {
        var body = buildBody(request, properties.model());
        return extractContent(executePost(body, request.timeout()));
    }

    @Override
    public <T> T generateStructured(LlmRequest request, Class<T> type) {
        var model = StringUtils.hasText(properties.structuredModel())
                ? properties.structuredModel()
                : properties.model();
        var body = buildBody(request, model);
        body.set("response_format", buildResponseFormat(type));
        var content = extractContent(executePost(body, request.timeout()));
        try {
            return objectMapper.readValue(content, type);
        } catch (Exception e) {
            throw new LlmException("Failed to deserialize structured LLM response", e);
        }
    }

    private ObjectNode buildBody(LlmRequest request, String model) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        var messages = body.putArray("messages");
        for (var msg : request.messages()) {
            var m = objectMapper.createObjectNode();
            m.put("role", msg.role().name().toLowerCase());
            m.put("content", msg.content());
            messages.add(m);
        }
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
        return body;
    }

    private ObjectNode buildResponseFormat(Class<?> type) {
        var rf = objectMapper.createObjectNode();
        rf.put("type", "json_schema");
        var js = rf.putObject("json_schema");
        js.put("name", type.getSimpleName());
        js.set("schema", schemaFactory.schemaFor(type));
        js.put("strict", true);
        return rf;
    }

    private JsonNode executePost(ObjectNode body, Duration timeoutOverride) {
        try {
            var client = timeoutOverride != null ? clientWithTimeout(timeoutOverride) : restClient;
            var response = client.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("choices").isArray() || response.path("choices").isEmpty()) {
                throw new LlmException("Empty or missing choices in LLM response");
            }
            return response;
        } catch (LlmException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new LlmException("Provider error: " + e.getStatusCode(), e, e.getStatusCode().value());
        } catch (Exception e) {
            throw new LlmException("LLM call failed", e);
        }
    }

    private String extractContent(JsonNode response) {
        var contentNode = response.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new LlmException("Missing content in LLM response choices");
        }
        return contentNode.asText();
    }

    private RestClient clientWithTimeout(Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return restClient.mutate().requestFactory(factory).build();
    }
}
