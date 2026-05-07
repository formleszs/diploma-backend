package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class QuizSubmitResponse {
    private Long lectureId;

    private int totalQuestions;
    private int correctAnswers;
    private int percent;

    private List<QuizAnswerResultResponse> results;
}