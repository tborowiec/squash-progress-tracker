package org.borowiec.squashprogresstracker.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.borowiec.squashprogresstracker.llm.dto.LlmRole;
import org.junit.jupiter.api.Test;

class MatchParsePromptBuilderTests {

    private final MatchParsePromptBuilder builder = new MatchParsePromptBuilder();

    @Test
    void build_userMessageContainsTodayDate() {
        var today = LocalDate.of(2026, 6, 4);
        var request = builder.build("beat Kowalski 3:1", today, List.of());
        var userMsg = userMessage(request);
        assertThat(userMsg).contains("2026-06-04");
    }

    @Test
    void build_userMessageContainsRawText() {
        var today = LocalDate.of(2026, 6, 4);
        var request = builder.build("beat Kowalski 3:1 on May 5th", today, List.of());
        assertThat(userMessage(request)).contains("beat Kowalski 3:1 on May 5th");
    }

    @Test
    void build_userMessageContainsKnownOpponentsHint() {
        var today = LocalDate.of(2026, 6, 4);
        var request = builder.build("lost to smith", today, List.of("Smith", "Nowak"));
        assertThat(userMessage(request)).contains("Smith").contains("Nowak");
    }

    @Test
    void build_noKnownOpponents_noOpponentHintLine() {
        var today = LocalDate.of(2026, 6, 4);
        var request = builder.build("won 3:0", today, List.of());
        assertThat(userMessage(request)).doesNotContain("Known opponents");
    }

    @Test
    void build_systemMessageContainsNoInventionRule() {
        var request = builder.build("text", LocalDate.now(), List.of());
        var sysMsg = request.messages().stream()
                .filter(m -> m.role() == LlmRole.SYSTEM)
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(sysMsg).containsIgnoringCase("invent");
    }

    private String userMessage(LlmRequest request) {
        return request.messages().stream()
                .filter(m -> m.role() == LlmRole.USER)
                .findFirst()
                .orElseThrow()
                .content();
    }
}
