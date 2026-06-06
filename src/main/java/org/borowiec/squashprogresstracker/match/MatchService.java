package org.borowiec.squashprogresstracker.match;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.borowiec.squashprogresstracker.match.dto.CreateOrUpdateMatchRequest;
import org.borowiec.squashprogresstracker.match.dto.MatchResponse;
import org.borowiec.squashprogresstracker.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchService {

    private final MatchRepository matchRepository;
    private final CurrentUser currentUser;
    private final EntityManager entityManager;

    public MatchService(MatchRepository matchRepository, CurrentUser currentUser, EntityManager entityManager) {
        this.matchRepository = matchRepository;
        this.currentUser = currentUser;
        this.entityManager = entityManager;
    }

    @Transactional
    public MatchResponse create(CreateOrUpdateMatchRequest request) {
        var match = new Match();
        match.setUserId(currentUser.currentUserId());
        match.setOpponentName(request.opponentName());
        match.setMatchDate(request.matchDate());
        match.setNotes(request.notes());

        applySets(match, request);

        return MatchResponse.from(matchRepository.save(match));
    }

    @Transactional(readOnly = true)
    public MatchResponse get(Long id) {
        return MatchResponse.from(requireOwned(id));
    }

    @Transactional
    public MatchResponse update(Long id, CreateOrUpdateMatchRequest request) {
        var match = requireOwned(id);
        match.setOpponentName(request.opponentName());
        match.setMatchDate(request.matchDate());
        match.setNotes(request.notes());

        // Replace all sets. Clear + flush BEFORE re-inserting so the orphan
        // DELETEs are sent ahead of the new INSERTs — otherwise reused
        // set_number values collide with uq_match_sets_match_set in one flush.
        match.getSets().clear();
        entityManager.flush();
        applySets(match, request);

        return MatchResponse.from(matchRepository.save(match));
    }

    @Transactional
    public void delete(Long id) {
        matchRepository.delete(requireOwned(id));
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> list(String opponentFilter) {
        var userId = currentUser.currentUserId();
        var matches = opponentFilter != null && !opponentFilter.isBlank()
                ? matchRepository.findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(
                        userId, opponentFilter)
                : matchRepository.findByUserIdOrderByMatchDateDescIdDesc(userId);
        return matches.stream().map(MatchResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<String> listOpponents() {
        return matchRepository.findDistinctOpponentNamesByUserId(currentUser.currentUserId());
    }

    private Match requireOwned(Long id) {
        return matchRepository
                .findByIdAndUserId(id, currentUser.currentUserId())
                .orElseThrow(() -> new MatchNotFoundException(id));
    }

    private void applySets(Match match, CreateOrUpdateMatchRequest request) {
        for (int i = 0; i < request.sets().size(); i++) {
            var sr = request.sets().get(i);
            var set = new MatchSet();
            set.setSetNumber(i + 1);
            set.setPlayerScore(sr.playerScore());
            set.setOpponentScore(sr.opponentScore());
            match.addSet(set);
        }
    }
}
