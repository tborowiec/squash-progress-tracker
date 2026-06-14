package org.borowiec.squashprogresstracker.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateLocaleRequest(
        @NotBlank @Pattern(regexp = "^(en|pl)$", message = "Unsupported locale") String locale) {}
