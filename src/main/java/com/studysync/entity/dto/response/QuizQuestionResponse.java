package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class QuizQuestionResponse {
    private Long flashcardId;
    private String question;
    private List<QuizOptionResponse> options;
}