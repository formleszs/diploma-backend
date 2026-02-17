package com.studysync.auth.dto;

public record AuthResponse(
        String token,
        UserResponse user
) {}
