package org.borowiec.squashprogresstracker.match.gameplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.borowiec.squashprogresstracker.llm.dto.LlmRole;
import org.borowiec.squashprogresstracker.match.Match;
import org.borowiec.squashprogresstracker.match.MatchSet;
import org.borowiec.squashprogresstracker.user.Locale;
import org.junit.jupiter.api.Test;

class GamePlanPromptBuilderTests {

    private final GamePlanPromptBuilder builder = new GamePlanPromptBuilder();

    @Test
    void build_userMessageContainsSetScoresAndNotes() {
        var match = makeMatch("2026-05-01", List.of(new int[] {11, 5}, new int[] {11, 7}), "Good game");
        var request = builder.build("Kowalski", List.of(match), Locale.EN);
        var userMsg = userMessage(request);
        assertThat(userMsg).contains("11–5");
        assertThat(userMsg).contains("11–7");
        assertThat(userMsg).contains("Good game");
    }

    @Test
    void build_systemMessageConstrainsToLoggedData() {
        var request = builder.build(
                "Kowalski", List.of(makeMatch("2026-05-01", List.of(new int[] {11, 5}), null)), Locale.EN);
        var sysMsg = systemMessage(request);
        assertThat(sysMsg).containsIgnoringCase("STRICTLY");
        assertThat(sysMsg).containsIgnoringCase("invent");
    }

    @Test
    void build_lowDataCaveat_presentWhenMatchesLessThan3() {
        var request = builder.build(
                "Kowalski", List.of(makeMatch("2026-05-01", List.of(new int[] {11, 5}), null)), Locale.EN);
        assertThat(userMessage(request)).containsIgnoringCase("limited");
    }

    @Test
    void build_lowDataCaveat_absentWhenMatchesAtLeast3() {
        var matches = List.of(
                makeMatch("2026-05-01", List.of(new int[] {11, 5}), null),
                makeMatch("2026-04-01", List.of(new int[] {9, 11}), null),
                makeMatch("2026-03-01", List.of(new int[] {11, 7}), null));
        var request = builder.build("Kowalski", matches, Locale.EN);
        assertThat(userMessage(request)).doesNotContainIgnoringCase("limited");
    }

    @Test
    void build_derivesResultFromSetScores() {
        var wonMatch = makeMatch("2026-05-01", List.of(new int[] {11, 5}, new int[] {11, 7}), null);
        assertThat(userMessage(builder.build("K", List.of(wonMatch), Locale.EN)))
                .contains("WON");

        var lostMatch = makeMatch("2026-05-01", List.of(new int[] {5, 11}, new int[] {7, 11}), null);
        assertThat(userMessage(builder.build("K", List.of(lostMatch), Locale.EN)))
                .contains("LOST");
    }

    @Test
    void build_withPolishLocale_systemMessageContainsPolishDirective() {
        var request = builder.build(
                "Kowalski", List.of(makeMatch("2026-05-01", List.of(new int[] {11, 5}), null)), Locale.PL);
        assertThat(systemMessage(request)).contains("polsku");
    }

    @Test
    void build_withEnglishLocale_systemMessageHasNoLanguageDirective() {
        var request = builder.build(
                "Kowalski", List.of(makeMatch("2026-05-01", List.of(new int[] {11, 5}), null)), Locale.EN);
        assertThat(systemMessage(request)).doesNotContain("polsku");
    }

    private String userMessage(LlmRequest request) {
        return request.messages().stream()
                .filter(m -> m.role() == LlmRole.USER)
                .findFirst()
                .orElseThrow()
                .content();
    }

    private String systemMessage(LlmRequest request) {
        return request.messages().stream()
                .filter(m -> m.role() == LlmRole.SYSTEM)
                .findFirst()
                .orElseThrow()
                .content();
    }

    private Match makeMatch(String date, List<int[]> setScores, String notes) {
        var match = new Match();
        match.setMatchDate(LocalDate.parse(date));
        match.setOpponentName("Kowalski");
        match.setNotes(notes);
        for (int i = 0; i < setScores.size(); i++) {
            var set = new MatchSet();
            set.setSetNumber(i + 1);
            set.setPlayerScore(setScores.get(i)[0]);
            set.setOpponentScore(setScores.get(i)[1]);
            match.addSet(set);
        }
        return match;
    }
}
