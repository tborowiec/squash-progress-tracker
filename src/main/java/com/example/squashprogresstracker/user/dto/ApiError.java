package com.example.squashprogresstracker.user.dto;

import java.util.Map;

public record ApiError(int status, String message, Map<String, String> fieldErrors) {

    public static ApiError of(int status, String message) {
        return new ApiError(status, message, null);
    }

    public static ApiError ofFields(int status, String message, Map<String, String> fieldErrors) {
        return new ApiError(status, message, fieldErrors);
    }
}
