package org.borowiec.squashprogresstracker.llm.client;

import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;

public interface LlmClient {

    String generate(LlmRequest request);

    <T> T generateStructured(LlmRequest request, Class<T> type);
}
