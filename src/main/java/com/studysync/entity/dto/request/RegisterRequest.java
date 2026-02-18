package com.studysync.entity.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class RegisterRequest {
    @Email
    @NotBlank
    String email;

    @NotBlank
    @Size(min = 6, max = 72)
    String password;

    @NotBlank
    @Size(max = 100)
    String displayName;
}