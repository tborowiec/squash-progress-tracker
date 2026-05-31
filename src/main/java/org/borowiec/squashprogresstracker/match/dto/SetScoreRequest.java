package org.borowiec.squashprogresstracker.match.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SetScoreRequest(
        @NotNull @Min(0) @Max(99) Integer playerScore,
        @NotNull @Min(0) @Max(99) Integer opponentScore
) {}
