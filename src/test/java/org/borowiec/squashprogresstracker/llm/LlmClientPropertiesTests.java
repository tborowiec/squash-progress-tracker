package org.borowiec.squashprogresstracker.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.borowiec.squashprogresstracker.llm.client.LlmClientConfig;
import org.borowiec.squashprogresstracker.llm.client.LlmClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LlmClientPropertiesTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LlmClientConfig.class);

    @Test
    void propertiesBindFromDefaults() {
        runner.withPropertyValues(
                        "llm.api-key=test-key",
                        "llm.base-url=https://generativelanguage.googleapis.com/v1beta/openai",
                        "llm.model=gemini-2.5-flash-lite",
                        "llm.structured-model=",
                        "llm.timeout=30s")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LlmClientProperties.class);
                    var props = ctx.getBean(LlmClientProperties.class);
                    assertThat(props.apiKey()).isEqualTo("test-key");
                    assertThat(props.baseUrl()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
                    assertThat(props.model()).isEqualTo("gemini-2.5-flash-lite");
                    assertThat(props.structuredModel()).isEmpty();
                    assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void llmRestClientBeanIsCreatable() {
        runner.withPropertyValues(
                        "llm.api-key=test-key",
                        "llm.base-url=https://generativelanguage.googleapis.com/v1beta/openai",
                        "llm.model=gemini-2.5-flash-lite",
                        "llm.structured-model=",
                        "llm.timeout=30s")
                .run(ctx -> {
                    assertThat(ctx).hasBean("llmRestClient");
                });
    }
}
