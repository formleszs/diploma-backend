package com.studysync.entity.dto.response;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QuizAnswerResultResponse {
    private Long flashcardId;

    private String selectedOptionId;
    private String correctOptionId;

    private boolean correct;
    private String correctAnswer;
}