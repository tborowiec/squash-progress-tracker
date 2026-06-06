package org.borowiec.squashprogresstracker.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.borowiec.squashprogresstracker.llm.dto.LlmMessage;
import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.borowiec.squashprogresstracker.llm.dto.LlmRole;
import org.junit.jupiter.api.Test;

class LlmConventionsTests {

    @Test
    void aiContent_of_setsAiGeneratedAndDisclaimerMentioningAdvice() {
        var wrapped = AiContent.of("some game plan");

        assertThat(wrapped.aiGenerated()).isTrue();
        assertThat(wrapped.content()).isEqualTo("some game plan");
        assertThat(wrapped.disclaimer()).containsIgnoringCase("advice");
        assertThat(wrapped.disclaimer()).containsIgnoringCase("not factual analysis");
    }

    @Test
    void llmRequest_ofUser_buildsSingleUserMessage() {
        var request = LlmRequest.ofUser("hello");

        assertThat(request.messages()).hasSize(1);
        LlmMessage msg = request.messages().get(0);
        assertThat(msg.role()).isEqualTo(LlmRole.USER);
        assertThat(msg.content()).isEqualTo("hello");
        assertThat(request.temperature()).isNull();
        assertThat(request.maxTokens()).isNull();
        assertThat(request.timeout()).isNull();
    }
}
