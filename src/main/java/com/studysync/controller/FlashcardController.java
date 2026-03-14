package com.studysync.controller;

import com.studysync.entity.dto.request.CreateFlashcardRequest;
import com.studysync.entity.dto.response.FlashcardResponse;
import com.studysync.entity.dto.response.FlashcardsListResponse;
import com.studysync.service.FlashcardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class FlashcardController {

    private final FlashcardService flashcardService;

    /**
     * Получить все карточки лекции + прогресс.
     * Первый вызов генерирует карточки (ленивая генерация).
     */
    @GetMapping("/api/lectures/{lectureId}/flashcards")
    public FlashcardsListResponse getFlashcards(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return flashcardService.getOrGenerateFlashcards(lectureId, authentication.getName());
    }

    /**
     * Добавить пользовательскую карточку.
     * Пользователь вводит определение → AI генерирует вопрос-ответ.
     */
    @PostMapping("/api/lectures/{lectureId}/flashcards")
    public FlashcardResponse createFlashcard(
            @PathVariable("lectureId") Long lectureId,
            @RequestBody CreateFlashcardRequest request,
            Authentication authentication
    ) {
        return flashcardService.createUserFlashcard(lectureId, request.getDefinition(), authentication.getName());
    }

    /**
     * Удалить карточку.
     */
    @DeleteMapping("/api/flashcards/{flashcardId}")
    public ResponseEntity<Void> deleteFlashcard(
            @PathVariable("flashcardId") Long flashcardId,
            Authentication authentication
    ) {
        flashcardService.deleteFlashcard(flashcardId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Отметить карточку как изученную / снять отметку.
     */
    @PatchMapping("/api/flashcards/{flashcardId}/studied")
    public FlashcardResponse toggleStudied(
            @PathVariable("flashcardId") Long flashcardId,
            @RequestParam("studied") boolean studied,
            Authentication authentication
    ) {
        return flashcardService.toggleStudied(flashcardId, studied, authentication.getName());
    }
}