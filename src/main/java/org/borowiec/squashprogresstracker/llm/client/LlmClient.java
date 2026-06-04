package org.borowiec.squashprogresstracker.llm.client;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;

import java.util.function.Consumer;

public interface LlmClient {

    String generate(LlmRequest request);

    <T> T generateStructured(LlmRequest request, Class<T> type);

    void generateStreaming(LlmRequest request, Consumer<String> onToken);
}
