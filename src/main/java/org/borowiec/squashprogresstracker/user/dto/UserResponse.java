package org.borowiec.squashprogresstracker.user.dto;

import org.borowiec.squashprogresstracker.user.User;

public record UserResponse(Long id, String email) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail());
    }
}
