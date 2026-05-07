package com.studysync.entity.dto.response;


import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class QuizResponse {
    private Long lectureId;
    private boolean available;
    private String message;

    private int totalCards;
    private int studiedCards;
    private int progressPercent;

    private List<QuizQuestionResponse> questions;
}
