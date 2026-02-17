package com.studysync.auth.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class UserResponse {
    Long id;
    String email;
    String displayName;
    String avatarUrl;
}