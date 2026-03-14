package com.studysync.service;

/**
 * Генерация краткого резюме (summary) лекции по её тексту.
 */
public interface SummaryGenerationService {

    /**
     * Генерирует summary по тексту лекции.
     * @param lectureText полный текст лекции
     * @return краткое резюме
     */
    String generateSummary(String lectureText);
}