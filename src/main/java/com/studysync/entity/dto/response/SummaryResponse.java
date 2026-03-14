package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SummaryResponse {
    private Long lectureId;
    private String summary;
    private String status; // "ready", "generating", "not_processed"

    // Конструктор для обратной совместимости
    public SummaryResponse(Long lectureId, String summary) {
        this.lectureId = lectureId;
        this.summary = summary;
        this.status = summary != null && !summary.isEmpty() ? "ready" : "not_processed";
    }
}