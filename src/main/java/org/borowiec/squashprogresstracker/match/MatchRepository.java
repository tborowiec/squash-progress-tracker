package org.borowiec.squashprogresstracker.match;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MatchRepository extends JpaRepository<Match, Long> {

    @EntityGraph(attributePaths = "sets")
    List<Match> findByUserIdOrderByMatchDateDescIdDesc(Long userId);

    @EntityGraph(attributePaths = "sets")
    List<Match> findByUserIdAndOpponentNameIgnoreCaseOrderByMatchDateDescIdDesc(Long userId, String opponentName);

    @EntityGraph(attributePaths = "sets")
    Optional<Match> findByIdAndUserId(Long id, Long userId);

    @Query(
            "SELECT min(m.opponentName) FROM Match m WHERE m.userId = :userId GROUP BY lower(m.opponentName) ORDER BY lower(min(m.opponentName))")
    List<String> findDistinctOpponentNamesByUserId(Long userId);
}
