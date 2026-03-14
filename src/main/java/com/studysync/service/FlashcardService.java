package com.studysync.service;

import com.studysync.entity.dto.response.FlashcardResponse;
import com.studysync.entity.dto.response.FlashcardsListResponse;

public interface FlashcardService {

    FlashcardsListResponse getOrGenerateFlashcards(Long lectureId, String userEmail);

    FlashcardResponse createUserFlashcard(Long lectureId, String definition, String userEmail);

    void deleteFlashcard(Long flashcardId, String userEmail);

    FlashcardResponse toggleStudied(Long flashcardId, boolean studied, String userEmail);
}