package org.borowiec.squashprogresstracker.llm.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "llm")
public record LlmClientProperties(
        String apiKey,
        String baseUrl,
        String model,
        String structuredModel,
        Duration timeout
) {
}
