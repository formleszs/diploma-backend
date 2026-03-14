package com.studysync.entity.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateFlashcardRequest {
    /**
     * Определение/тема, которую пользователь хочет видеть в карточке.
     * AI сгенерирует вопрос и ответ на основе этого.
     */
    private String definition;
}