package com.studysync.entity.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuizAnswerRequest {
    private Long flashcardId;
    private String selectedOptionId;
}