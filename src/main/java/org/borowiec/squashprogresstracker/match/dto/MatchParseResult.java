package org.borowiec.squashprogresstracker.match.dto;

import java.util.List;

public record MatchParseResult(String opponentName, String matchDate, String notes, List<ParsedSet> sets) {
    public record ParsedSet(Integer playerScore, Integer opponentScore) {}
}
