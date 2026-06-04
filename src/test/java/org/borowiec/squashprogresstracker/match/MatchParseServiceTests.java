package org.borowiec.squashprogresstracker.match;

import org.borowiec.squashprogresstracker.llm.client.LlmClient;
import org.borowiec.squashprogresstracker.llm.client.LlmException;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult.ParsedSet;
import org.borowiec.squashprogresstracker.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchParseServiceTests {

    @Mock LlmClient llmClient;
    @Mock MatchRepository matchRepository;
    @Mock CurrentUser currentUser;

    MatchParseService service;

    @BeforeEach
    void setUp() {
        service = new MatchParseService(llmClient, matchRepository, currentUser, new MatchParsePromptBuilder());
        when(currentUser.currentUserId()).thenReturn(1L);
        when(matchRepository.findDistinctOpponentNamesByUserId(1L)).thenReturn(List.of("Kowalski"));
    }

    @Test
    void parse_happyPath_returnsLlmResult() {
        var expected = new MatchParseResult(
                "Kowalski", "2026-05-05", "struggled in the second set",
                List.of(new ParsedSet(11, 5), new ParsedSet(6, 11), new ParsedSet(11, 2), new ParsedSet(11, 1))
        );
        when(llmClient.generateStructured(any(), eq(MatchParseResult.class))).thenReturn(expected);

        var result = service.parse("beat Kowalski 3:1 on May 5th");

        assertThat(result).isEqualTo(expected);
        assertThat(result.sets()).hasSize(4);
    }

    @Test
    void parse_emptyPartialFields_returnedAsEmpties() {
        var expected = new MatchParseResult("", "", "", List.of());
        when(llmClient.generateStructured(any(), eq(MatchParseResult.class))).thenReturn(expected);

        var result = service.parse("something vague");

        assertThat(result.opponentName()).isEmpty();
        assertThat(result.matchDate()).isEmpty();
        assertThat(result.sets()).isEmpty();
    }

    @Test
    void parse_promptIncludesTodayAndKnownOpponents() {
        when(llmClient.generateStructured(any(), eq(MatchParseResult.class)))
                .thenReturn(new MatchParseResult("Kowalski", "2026-06-04", "", List.of()));

        service.parse("won today");

        var captor = ArgumentCaptor.forClass(org.borowiec.squashprogresstracker.llm.dto.LlmRequest.class);
        verify(llmClient).generateStructured(captor.capture(), eq(MatchParseResult.class));
        var userMsg = captor.getValue().messages().stream()
                .filter(m -> m.role() == org.borowiec.squashprogresstracker.llm.dto.LlmRole.USER)
                .findFirst().orElseThrow().content();
        assertThat(userMsg).contains("Kowalski");
        // date is dynamic (LocalDate.now()), just verify it looks like an ISO date
        assertThat(userMsg).matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*");
    }

    @Test
    void parse_llmException_propagates() {
        when(llmClient.generateStructured(any(), eq(MatchParseResult.class)))
                .thenThrow(new LlmException("provider down"));

        assertThatThrownBy(() -> service.parse("any text"))
                .isInstanceOf(LlmException.class)
                .hasMessage("provider down");
    }
}
