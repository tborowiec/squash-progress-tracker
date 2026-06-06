package org.borowiec.squashprogresstracker.match;

import java.time.LocalDate;
import java.util.List;
import org.borowiec.squashprogresstracker.llm.dto.LlmMessage;
import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.borowiec.squashprogresstracker.llm.dto.LlmRole;
import org.springframework.stereotype.Component;

@Component
public class MatchParsePromptBuilder {

    private static final String SYSTEM_MESSAGE =
            """
            You are a squash match parser. Extract structured match data from a player's free-text description.
            Rules:
            - Fill every field as best you can from the text. Never invent scores, opponents, or dates not implied by the text.
            - For opponentName: use the name as written; if the text clearly refers to a known opponent (see hints), use that exact name.
            - For matchDate: resolve relative phrases ("yesterday", "last Monday", "May 5th") using today's date provided in the user message. If no date is mentioned, default to today.
            - For sets: parse each set score as playerScore vs opponentScore in the order played.
            - For notes: include any context, observations, or remarks from the text.
            - If a field cannot be determined, return an empty string (opponentName, matchDate, notes) or an empty array (sets). Do not omit fields.""";

    public LlmRequest build(String text, LocalDate today, List<String> knownOpponents) {
        var system = new LlmMessage(LlmRole.SYSTEM, SYSTEM_MESSAGE);
        var user = new LlmMessage(LlmRole.USER, buildUserMessage(text, today, knownOpponents));
        return new LlmRequest(List.of(system, user), null, null, null);
    }

    private String buildUserMessage(String text, LocalDate today, List<String> knownOpponents) {
        var sb = new StringBuilder();
        sb.append("Today's date: ").append(today).append("\n");
        if (!knownOpponents.isEmpty()) {
            sb.append("Known opponents (snap to exact name if the text clearly refers to one of these): ")
                    .append(String.join(", ", knownOpponents))
                    .append("\n");
        }
        sb.append("\nMatch description:\n").append(text);
        return sb.toString();
    }
}
