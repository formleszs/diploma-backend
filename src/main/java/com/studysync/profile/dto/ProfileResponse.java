package com.studysync.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class ProfileResponse {
    Long id;
    String email;
    String displayName;
    String avatarUrl;
}
