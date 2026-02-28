package com.studysync.entity.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VisionTranscriptionResponse {

    private List<Page> pages;

    // Эти два поля модель НЕ обязана отдавать.
    // Мы заполним их после парсинга в Java.
    private int uncertainCountTotal;
    private int charsTotal;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        private int index;
        private String text;

        // Мы будем пересчитывать сами (не доверяем модели).
        private int uncertainCount;
    }
}