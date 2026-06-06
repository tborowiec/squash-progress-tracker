package org.borowiec.squashprogresstracker.match.gameplan;

import java.util.List;
import org.borowiec.squashprogresstracker.llm.dto.LlmMessage;
import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.borowiec.squashprogresstracker.llm.dto.LlmRole;
import org.borowiec.squashprogresstracker.match.Match;
import org.springframework.stereotype.Component;

@Component
public class GamePlanPromptBuilder {

    static final int LOW_DATA_THRESHOLD = 3;

    private static final String SYSTEM_MESSAGE =
            """
            You are a squash coach producing a tactical game plan for a player's next match against a specific opponent.
            Your analysis must be grounded STRICTLY in the match history provided — set-by-set scores, overall results, and the player's notes.
            Do NOT invent statistics, scores, or patterns not present in the data.
            Do NOT give generic conditioning, fitness, or mental-game advice unrelated to the actual match data.
            Output concise, actionable prose: tactical patterns you observe and suggested tactics for the next match.""";

    public LlmRequest build(String opponentName, List<Match> matches) {
        var system = new LlmMessage(LlmRole.SYSTEM, SYSTEM_MESSAGE);
        var user = new LlmMessage(LlmRole.USER, buildUserMessage(opponentName, matches));
        return new LlmRequest(List.of(system, user), null, null, null);
    }

    private String buildUserMessage(String opponentName, List<Match> matches) {
        var sb = new StringBuilder();
        sb.append("Opponent: ").append(opponentName).append("\n\n");
        sb.append("Match history (newest first):\n");

        for (int i = 0; i < matches.size(); i++) {
            var m = matches.get(i);
            var setsWon = (int) m.getSets().stream()
                    .filter(s -> s.getPlayerScore() > s.getOpponentScore())
                    .count();
            var setsLost = (int) m.getSets().stream()
                    .filter(s -> s.getPlayerScore() < s.getOpponentScore())
                    .count();
            var result = setsWon > setsLost ? "WON" : (setsLost > setsWon ? "LOST" : "DRAW");

            sb.append("\nMatch ")
                    .append(i + 1)
                    .append(": ")
                    .append(m.getMatchDate())
                    .append(" — ")
                    .append(result)
                    .append(" (")
                    .append(setsWon)
                    .append("–")
                    .append(setsLost)
                    .append(" sets)\n");

            for (var set : m.getSets()) {
                sb.append("  Set ")
                        .append(set.getSetNumber())
                        .append(": ")
                        .append(set.getPlayerScore())
                        .append("–")
                        .append(set.getOpponentScore())
                        .append("\n");
            }
            if (m.getNotes() != null && !m.getNotes().isBlank()) {
                sb.append("  Notes: ").append(m.getNotes()).append("\n");
            }
        }

        if (matches.size() < LOW_DATA_THRESHOLD) {
            sb.append("\nNote: This plan is based on limited match history (")
                    .append(matches.size())
                    .append(" match(es)). ")
                    .append("Acknowledge this limitation and keep confidence appropriately low.\n");
        }

        sb.append("\nBased on this history, provide a tactical game plan for the next match against ")
                .append(opponentName)
                .append(".");
        return sb.toString();
    }
}
