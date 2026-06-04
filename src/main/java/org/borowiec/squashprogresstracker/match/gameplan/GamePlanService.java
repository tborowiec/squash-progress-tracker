package org.borowiec.squashprogresstracker.match.gameplan;

import org.borowiec.squashprogresstracker.llm.client.LlmClient;
import org.borowiec.squashprogresstracker.match.MatchRepository;
import org.borowiec.squashprogresstracker.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

@Service
public class GamePlanService {

    private final MatchRepository matchRepository;
    private final CurrentUser currentUser;
    private final LlmClient llmClient;
    private final GamePlanPromptBuilder promptBuilder;

    public GamePlanService(MatchRepository matchRepository, CurrentUser currentUser,
                           LlmClient llmClient, GamePlanPromptBuilder promptBuilder) {
        this.matchRepository = matchRepository;
        this.currentUser = currentUser;
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Runs on the request thread: resolves the authenticated user, loads matches,
     * builds the LlmRequest. The transaction closes when this method returns —
     * the DB connection is never held across the LLM stream.
     */
    @Transactional(readOnly = true)
    public GamePlanContext prepare(String opponentName) {
        var userId = currentUser.currentUserId();
        var matches = matchRepository
                .findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(userId, opponentName);
        if (matches.isEmpty()) {
            throw new GamePlanUnavailableException(opponentName);
        }
        var request = promptBuilder.build(opponentName, matches);
        var lowData = matches.size() < GamePlanPromptBuilder.LOW_DATA_THRESHOLD;
        return new GamePlanContext(request, matches.size(), lowData);
    }

    public void stream(GamePlanContext context, Consumer<String> onToken) {
        llmClient.generateStreaming(context.request(), onToken);
    }
}
