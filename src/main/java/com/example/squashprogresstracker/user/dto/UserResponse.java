package com.example.squashprogresstracker.user.dto;

import com.example.squashprogresstracker.user.User;

public record UserResponse(Long id, String email) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail());
    }
}
