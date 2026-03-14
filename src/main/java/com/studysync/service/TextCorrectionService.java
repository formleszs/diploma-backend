package com.studysync.service;

import java.nio.file.Path;

public interface TextCorrectionService {

    /**
     * Коррекция текста с использованием изображения (vision-based).
     * Модель видит И оригинальное фото И распознанный текст — может сравнить и найти ошибки.
     */
    String correctWithImage(String ocrText, Path imagePath);

    /**
     * Коррекция текста без изображения (text-only fallback).
     */
    String correctRecognizedText(String text);
}