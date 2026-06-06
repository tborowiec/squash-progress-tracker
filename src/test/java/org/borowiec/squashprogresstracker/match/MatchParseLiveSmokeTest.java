package org.borowiec.squashprogresstracker.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.borowiec.squashprogresstracker.llm.client.LlmClientConfig;
import org.borowiec.squashprogresstracker.llm.client.LlmClientProperties;
import org.borowiec.squashprogresstracker.llm.client.OpenAiCompatLlmClient;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/**
 * Live parse smoke test — requires a real LLM_API_KEY. Auto-skips in CI.
 *
 * Run with: LLM_API_KEY=<key> ./mvnw test -Dtest=MatchParseLiveSmokeTest
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class MatchParseLiveSmokeTest {

    @Test
    void liveParse_representativeSentence_returnsPlausibleResult() {
        var llmClient = buildClient();
        var promptBuilder = new MatchParsePromptBuilder();

        var text = "beat Kowalski 3:1 (11:5, 6:11, 11:2, 11:1) on May 5th, struggled in the second set";
        var request = promptBuilder.build(text, LocalDate.of(2026, 6, 4), List.of());
        var result = llmClient.generateStructured(request, MatchParseResult.class);

        assertThat(result).isNotNull();
        assertThat(result.opponentName()).isNotBlank();
        assertThat(result.sets()).hasSizeGreaterThanOrEqualTo(1);
    }

    private OpenAiCompatLlmClient buildClient() {
        var env = System.getenv();
        var props = new LlmClientProperties(
                env.get("LLM_API_KEY"),
                env.getOrDefault("LLM_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai"),
                env.getOrDefault("LLM_MODEL", "gemini-2.5-flash-lite"),
                env.getOrDefault("LLM_STRUCTURED_MODEL", ""),
                Duration.ofSeconds(30));
        return new OpenAiCompatLlmClient(new LlmClientConfig().llmRestClient(props), props, new ObjectMapper());
    }
}
