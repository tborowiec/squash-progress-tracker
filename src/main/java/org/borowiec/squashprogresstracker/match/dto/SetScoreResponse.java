package org.borowiec.squashprogresstracker.match.dto;

import org.borowiec.squashprogresstracker.match.MatchSet;

public record SetScoreResponse(Integer setNumber, Integer playerScore, Integer opponentScore) {
    public static SetScoreResponse from(MatchSet set) {
        return new SetScoreResponse(set.getSetNumber(), set.getPlayerScore(), set.getOpponentScore());
    }
}
