package org.borowiec.squashprogresstracker.match;

import java.time.LocalDate;
import org.borowiec.squashprogresstracker.llm.client.LlmClient;
import org.borowiec.squashprogresstracker.match.dto.MatchParseResult;
import org.borowiec.squashprogresstracker.security.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class MatchParseService {

    private final LlmClient llmClient;
    private final MatchRepository matchRepository;
    private final CurrentUser currentUser;
    private final MatchParsePromptBuilder promptBuilder;

    public MatchParseService(
            LlmClient llmClient,
            MatchRepository matchRepository,
            CurrentUser currentUser,
            MatchParsePromptBuilder promptBuilder) {
        this.llmClient = llmClient;
        this.matchRepository = matchRepository;
        this.currentUser = currentUser;
        this.promptBuilder = promptBuilder;
    }

    public MatchParseResult parse(String text) {
        var knownOpponents = matchRepository.findDistinctOpponentNamesByUserId(currentUser.currentUserId());
        var request = promptBuilder.build(text, LocalDate.now(), knownOpponents);
        return llmClient.generateStructured(request, MatchParseResult.class);
    }
}
