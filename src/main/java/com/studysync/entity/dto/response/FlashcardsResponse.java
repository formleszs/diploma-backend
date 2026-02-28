package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FlashcardsResponse {
    private Long lectureId;
    // Строкой JSON, чтобы не городить парсинг прямо сейчас
    private String flashcardsJson;
}