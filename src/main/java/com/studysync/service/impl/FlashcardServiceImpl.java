package com.studysync.service.impl;

import com.studysync.entity.*;
import com.studysync.entity.dto.response.*;
import com.studysync.repository.*;
import com.studysync.service.FlashcardGenerationService;
import com.studysync.service.FlashcardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlashcardServiceImpl implements FlashcardService {

    private final FlashcardRepository flashcardRepository;
    private final LectureRepository lectureRepository;
    private final LectureTextRepository lectureTextRepository;
    private final UserRepository userRepository;
    private final FlashcardGenerationService flashcardGenerationService;

    @Override
    @Transactional
    public FlashcardsListResponse getOrGenerateFlashcards(Long lectureId, String userEmail) {
        Lecture lecture = getLectureOwned(lectureId, userEmail);

        if (lecture.getStatus() != com.studysync.enums.LectureStatus.PROCESSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Лекция ещё не обработана");
        }

        // Проверяем кэш
        if (!flashcardRepository.existsByLectureId(lectureId)) {
            // Генерируем карточки
            LectureText lectureText = lectureTextRepository.findByLectureId(lectureId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Текст лекции не найден"));

            List<String[]> generated = flashcardGenerationService.generateFlashcards(lectureText.getContent());

            for (int i = 0; i < generated.size(); i++) {
                String[] qa = generated.get(i);
                Flashcard card = new Flashcard();
                card.setLecture(lecture);
                card.setQuestion(qa[0]);
                card.setAnswer(qa[1]);
                card.setSortOrder(i);
                card.setUserCreated(false);
                card.setStudied(false);
                flashcardRepository.save(card);
            }

            log.info("Lecture {}: {} flashcards generated and saved", lectureId, generated.size());
        }

        return buildResponse(lectureId);
    }

    @Override
    @Transactional
    public FlashcardResponse createUserFlashcard(Long lectureId, String definition, String userEmail) {
        Lecture lecture = getLectureOwned(lectureId, userEmail);

        if (definition == null || definition.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Определение не может быть пустым");
        }

        // Получаем текст лекции для контекста
        String lectureTextContent = "";
        LectureText lt = lectureTextRepository.findByLectureId(lectureId).orElse(null);
        if (lt != null) {
            lectureTextContent = lt.getContent();
        }

        // Генерируем вопрос-ответ по определению
        String[] qa = flashcardGenerationService.generateSingleFlashcard(definition, lectureTextContent);

        // Определяем sortOrder — в конец
        long count = flashcardRepository.countByLectureId(lectureId);

        Flashcard card = new Flashcard();
        card.setLecture(lecture);
        card.setQuestion(qa[0]);
        card.setAnswer(qa[1]);
        card.setSortOrder((int) count);
        card.setUserCreated(true);
        card.setStudied(false);
        flashcardRepository.save(card);

        log.info("Lecture {}: user flashcard created. id={}", lectureId, card.getId());

        return toResponse(card);
    }

    @Override
    @Transactional
    public void deleteFlashcard(Long flashcardId, String userEmail) {
        Flashcard card = getFlashcardOwned(flashcardId, userEmail);
        flashcardRepository.delete(card);
        log.info("Flashcard {} deleted", flashcardId);
    }

    @Override
    @Transactional
    public FlashcardResponse toggleStudied(Long flashcardId, boolean studied, String userEmail) {
        Flashcard card = getFlashcardOwned(flashcardId, userEmail);
        card.setStudied(studied);
        flashcardRepository.save(card);
        return toResponse(card);
    }

    // ============================================================
    //  Private helpers
    // ============================================================

    private FlashcardsListResponse buildResponse(Long lectureId) {
        List<Flashcard> cards = flashcardRepository.findByLectureIdOrderBySortOrderAsc(lectureId);
        long total = cards.size();
        long studied = cards.stream().filter(Flashcard::isStudied).count();
        int percent = total == 0 ? 0 : (int) Math.round(studied * 100.0 / total);

        List<FlashcardResponse> cardResponses = cards.stream()
                .map(this::toResponse)
                .toList();

        return new FlashcardsListResponse(
                lectureId,
                cardResponses,
                (int) total,
                (int) studied,
                percent,
                percent == 100 && total > 0
        );
    }

    private FlashcardResponse toResponse(Flashcard card) {
        return new FlashcardResponse(
                card.getId(),
                card.getQuestion(),
                card.getAnswer(),
                card.getSortOrder(),
                card.isUserCreated(),
                card.isStudied()
        );
    }

    private Lecture getLectureOwned(Long lectureId, String userEmail) {
        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Лекция не найдена"));

        if (lecture.getProject() == null || lecture.getProject().getOwner() == null ||
                !Objects.equals(lecture.getProject().getOwner().getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        return lecture;
    }

    private Flashcard getFlashcardOwned(Long flashcardId, String userEmail) {
        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Flashcard card = flashcardRepository.findById(flashcardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена"));

        Lecture lecture = card.getLecture();
        if (lecture == null || lecture.getProject() == null || lecture.getProject().getOwner() == null ||
                !Objects.equals(lecture.getProject().getOwner().getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        return card;
    }
}