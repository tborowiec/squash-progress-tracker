package org.borowiec.squashprogresstracker.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email String email, @Size(min = 8, message = "Password must be at least 8 characters") String password) {}
