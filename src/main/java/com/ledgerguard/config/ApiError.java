package com.ledgerguard.config;

import java.time.Instant;
import java.util.List;

public record ApiError(String error, String message, List<String> details, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, List.of(), Instant.now());
    }

    public static ApiError of(String error, String message, List<String> details) {
        return new ApiError(error, message, details, Instant.now());
    }
}
