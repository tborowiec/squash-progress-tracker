package org.borowiec.squashprogresstracker.llm.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmClientProperties(
        String apiKey, String baseUrl, String model, String structuredModel, Duration timeout) {}
