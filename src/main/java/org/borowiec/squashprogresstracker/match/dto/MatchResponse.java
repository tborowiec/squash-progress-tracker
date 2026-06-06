package org.borowiec.squashprogresstracker.match.dto;

import java.time.LocalDate;
import java.util.List;
import org.borowiec.squashprogresstracker.match.Match;
import org.borowiec.squashprogresstracker.match.MatchResult;

public record MatchResponse(
        Long id,
        String opponentName,
        LocalDate matchDate,
        String notes,
        List<SetScoreResponse> sets,
        int setsWon,
        int setsLost,
        MatchResult result) {
    public static MatchResponse from(Match match) {
        var sets = match.getSets().stream().map(SetScoreResponse::from).toList();
        var setsWon = (int) match.getSets().stream()
                .filter(s -> s.getPlayerScore() > s.getOpponentScore())
                .count();
        var setsLost = (int) match.getSets().stream()
                .filter(s -> s.getPlayerScore() < s.getOpponentScore())
                .count();
        var result = setsWon > setsLost ? MatchResult.WON : setsLost > setsWon ? MatchResult.LOST : MatchResult.DRAW;
        return new MatchResponse(
                match.getId(),
                match.getOpponentName(),
                match.getMatchDate(),
                match.getNotes(),
                sets,
                setsWon,
                setsLost,
                result);
    }
}
