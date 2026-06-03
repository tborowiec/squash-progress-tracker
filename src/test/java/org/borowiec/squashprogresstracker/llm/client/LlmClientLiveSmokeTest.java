package org.borowiec.squashprogresstracker.llm.client;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual/local smoke test — requires a real LLM_API_KEY. Auto-skips in CI.
 *
 * Run with: LLM_API_KEY=<key> ./mvnw test -Dtest=LlmClientLiveSmokeTest
 *
 * The structured-output test is the load-bearing gate: it confirms Gemini's
 * OpenAI-compat layer actually returns deserializable typed JSON (something
 * mock tests cannot verify).
 *
 * Use the paid/no-training Gemini tier for any non-synthetic prompts (see
 * research.md two-tier strategy). Free tier is fine for throwaway smoke prompts.
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class LlmClientLiveSmokeTest {

    record PongResponse(String word) {}

    @Test
    void liveGenerate_returnsCoherentCompletion() {
        var response = buildClient().generate(LlmRequest.ofUser("Reply with the single word: pong"));
        assertThat(response).isNotBlank();
    }

    @Test
    void liveGenerateStructured_deserializesTypedResponse() {
        var request = LlmRequest.ofUser(
                "Reply with a JSON object with exactly one field: \"word\" set to the value \"pong\".");
        var result = buildClient().generateStructured(request, PongResponse.class);
        assertThat(result).isNotNull();
        assertThat(result.word()).isNotBlank();
    }

    private OpenAiCompatLlmClient buildClient() {
        var env = System.getenv();
        var props = new LlmClientProperties(
                env.get("LLM_API_KEY"),
                env.getOrDefault("LLM_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai"),
                env.getOrDefault("LLM_MODEL", "gemini-2.5-flash-lite"),
                env.getOrDefault("LLM_STRUCTURED_MODEL", ""),
                Duration.ofSeconds(30)
        );
        return new OpenAiCompatLlmClient(new LlmClientConfig().llmRestClient(props), props, new ObjectMapper());
    }
}
