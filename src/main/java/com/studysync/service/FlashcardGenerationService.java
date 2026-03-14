package com.studysync.service;

import java.util.List;

/**
 * Генерация flashcards по тексту лекции.
 */
public interface FlashcardGenerationService {

    /**
     * Генерирует список карточек (вопрос-ответ) по тексту лекции.
     * Возвращает список пар [question, answer].
     */
    List<String[]> generateFlashcards(String lectureText);

    /**
     * Генерирует одну карточку по определению/теме пользователя
     * в контексте текста лекции.
     * Возвращает [question, answer].
     */
    String[] generateSingleFlashcard(String definition, String lectureText);
}