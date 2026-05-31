package org.borowiec.squashprogresstracker.match;

import org.borowiec.squashprogresstracker.match.dto.CreateMatchRequest;
import org.borowiec.squashprogresstracker.match.dto.MatchResponse;
import org.borowiec.squashprogresstracker.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MatchService {

    private final MatchRepository matchRepository;
    private final CurrentUser currentUser;

    public MatchService(MatchRepository matchRepository, CurrentUser currentUser) {
        this.matchRepository = matchRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public MatchResponse create(CreateMatchRequest request) {
        var match = new Match();
        match.setUserId(currentUser.currentUserId());
        match.setOpponentName(request.opponentName());
        match.setMatchDate(request.matchDate());
        match.setNotes(request.notes());

        for (int i = 0; i < request.sets().size(); i++) {
            var sr = request.sets().get(i);
            var set = new MatchSet();
            set.setSetNumber(i + 1);
            set.setPlayerScore(sr.playerScore());
            set.setOpponentScore(sr.opponentScore());
            match.addSet(set);
        }

        return MatchResponse.from(matchRepository.save(match));
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> list(String opponentFilter) {
        var userId = currentUser.currentUserId();
        var matches = opponentFilter != null && !opponentFilter.isBlank()
                ? matchRepository.findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(userId, opponentFilter)
                : matchRepository.findByUserIdOrderByMatchDateDescIdDesc(userId);
        return matches.stream().map(MatchResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<String> listOpponents() {
        return matchRepository.findDistinctOpponentNamesByUserId(currentUser.currentUserId());
    }
}
