package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QuizLockedResponse {
    private boolean available;
    private String message;
}