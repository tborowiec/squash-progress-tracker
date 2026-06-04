package org.borowiec.squashprogresstracker.match.gameplan;

public class GamePlanUnavailableException extends RuntimeException {

    public GamePlanUnavailableException(String opponentName) {
        super("No match history for opponent: " + opponentName);
    }
}
