package org.borowiec.squashprogresstracker.llm.dto;

import java.time.Duration;
import java.util.List;

public record LlmRequest(
        List<LlmMessage> messages,
        Double temperature,
        Integer maxTokens,
        Duration timeout
) {

    public static LlmRequest ofUser(String content) {
        return new LlmRequest(List.of(new LlmMessage(LlmRole.USER, content)), null, null, null);
    }
}
