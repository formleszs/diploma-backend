package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class FlashcardsListResponse {
    private Long lectureId;
    private List<FlashcardResponse> cards;
    private int totalCards;
    private int studiedCards;
    private int progressPercent; // 0-100
    private boolean quizAvailable; // true когда progressPercent == 100
}