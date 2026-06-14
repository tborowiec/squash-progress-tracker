package org.borowiec.squashprogresstracker.match.gameplan;

import java.util.function.Consumer;
import org.borowiec.squashprogresstracker.llm.client.LlmClient;
import org.borowiec.squashprogresstracker.match.MatchRepository;
import org.borowiec.squashprogresstracker.security.CurrentUser;
import org.borowiec.squashprogresstracker.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GamePlanService {

    private final MatchRepository matchRepository;
    private final CurrentUser currentUser;
    private final LlmClient llmClient;
    private final GamePlanPromptBuilder promptBuilder;
    private final UserRepository userRepository;

    public GamePlanService(
            MatchRepository matchRepository,
            CurrentUser currentUser,
            LlmClient llmClient,
            GamePlanPromptBuilder promptBuilder,
            UserRepository userRepository) {
        this.matchRepository = matchRepository;
        this.currentUser = currentUser;
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.userRepository = userRepository;
    }

    /**
     * Runs on the request thread: resolves the authenticated user, loads matches,
     * builds the LlmRequest. The transaction closes when this method returns —
     * the DB connection is never held across the LLM stream.
     */
    @Transactional(readOnly = true)
    public GamePlanContext prepare(String opponentName) {
        var userId = currentUser.currentUserId();
        var user = userRepository.findById(userId).orElseThrow();
        var locale = user.getLocale();
        var matches =
                matchRepository.findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(userId, opponentName);
        if (matches.isEmpty()) {
            throw new GamePlanUnavailableException(opponentName);
        }
        var request = promptBuilder.build(opponentName, matches, locale);
        var lowData = matches.size() < GamePlanPromptBuilder.LOW_DATA_THRESHOLD;
        return new GamePlanContext(request, matches.size(), lowData, locale);
    }

    public void stream(GamePlanContext context, Consumer<String> onToken) {
        llmClient.generateStreaming(context.request(), onToken);
    }
}
