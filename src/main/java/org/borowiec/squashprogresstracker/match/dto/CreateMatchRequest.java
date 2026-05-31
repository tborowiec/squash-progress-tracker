package org.borowiec.squashprogresstracker.match.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateMatchRequest(
        @NotBlank @Size(max = 255) String opponentName,
        @NotNull @PastOrPresent LocalDate matchDate,
        @Size(max = 2000) String notes,
        @NotEmpty @Size(max = 5) @Valid List<SetScoreRequest> sets
) {}
