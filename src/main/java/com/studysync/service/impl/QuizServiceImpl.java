package com.studysync.service.impl;

import com.studysync.entity.Flashcard;
import com.studysync.entity.Lecture;
import com.studysync.entity.User;
import com.studysync.entity.dto.request.QuizAnswerRequest;
import com.studysync.entity.dto.request.QuizSubmitRequest;
import com.studysync.entity.dto.response.*;
import com.studysync.repository.FlashcardRepository;
import com.studysync.repository.LectureRepository;
import com.studysync.repository.UserRepository;
import com.studysync.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {

    private static final List<String> OPTION_IDS = List.of("A", "B", "C", "D");

    private final FlashcardRepository flashcardRepository;
    private final LectureRepository lectureRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public QuizResponse getQuiz(Long lectureId, String userEmail) {
        getLectureOwned(lectureId, userEmail);

        List<Flashcard> cards = flashcardRepository.findByLectureIdOrderBySortOrderAsc(lectureId);

        if (cards.isEmpty()) {
            return new QuizResponse(
                    lectureId,
                    false,
                    "Тест недоступен. Сначала необходимо создать или сгенерировать карточки.",
                    0,
                    0,
                    0,
                    List.of()
            );
        }

        int total = cards.size();
        int studied = (int) cards.stream().filter(Flashcard::isStudied).count();
        int progress = (int) Math.round(studied * 100.0 / total);

        if (progress < 100) {
            return new QuizResponse(
                    lectureId,
                    false,
                    "Тест откроется после изучения всех карточек.",
                    total,
                    studied,
                    progress,
                    List.of()
            );
        }

        List<QuizQuestionResponse> questions = cards.stream()
                .map(card -> toQuizQuestion(lectureId, card, cards))
                .toList();

        return new QuizResponse(
                lectureId,
                true,
                "Тест доступен.",
                total,
                studied,
                progress,
                questions
        );
    }

    @Override
    @Transactional(readOnly = true)
    public QuizSubmitResponse submitQuiz(Long lectureId, QuizSubmitRequest request, String userEmail) {
        getLectureOwned(lectureId, userEmail);

        List<Flashcard> cards = flashcardRepository.findByLectureIdOrderBySortOrderAsc(lectureId);

        if (cards.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Карточки для теста не найдены");
        }

        int total = cards.size();
        int studied = (int) cards.stream().filter(Flashcard::isStudied).count();
        int progress = (int) Math.round(studied * 100.0 / total);

        if (progress < 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Тест заблокирован до изучения всех карточек");
        }

        Map<Long, QuizAnswerRequest> answersByCardId = Optional.ofNullable(request.getAnswers())
                .orElse(List.of())
                .stream()
                .filter(answer -> answer.getFlashcardId() != null)
                .collect(Collectors.toMap(
                        QuizAnswerRequest::getFlashcardId,
                        answer -> answer,
                        (first, second) -> second
                ));

        List<QuizAnswerResultResponse> results = new ArrayList<>();
        int correct = 0;

        for (Flashcard card : cards) {
            QuizQuestionResponse question = toQuizQuestion(lectureId, card, cards);
            String correctOptionId = findCorrectOptionId(question, card.getAnswer());

            QuizAnswerRequest userAnswer = answersByCardId.get(card.getId());
            String selectedOptionId = userAnswer == null ? null : userAnswer.getSelectedOptionId();

            boolean isCorrect = correctOptionId != null && correctOptionId.equals(selectedOptionId);
            if (isCorrect) {
                correct++;
            }

            results.add(new QuizAnswerResultResponse(
                    card.getId(),
                    selectedOptionId,
                    correctOptionId,
                    isCorrect,
                    card.getAnswer()
            ));
        }

        int percent = total == 0 ? 0 : (int) Math.round(correct * 100.0 / total);

        return new QuizSubmitResponse(
                lectureId,
                total,
                correct,
                percent,
                results
        );
    }

    private QuizQuestionResponse toQuizQuestion(Long lectureId, Flashcard currentCard, List<Flashcard> allCards) {
        List<String> options = buildAnswerOptions(lectureId, currentCard, allCards);

        List<QuizOptionResponse> optionResponses = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            optionResponses.add(new QuizOptionResponse(OPTION_IDS.get(i), options.get(i)));
        }

        return new QuizQuestionResponse(
                currentCard.getId(),
                currentCard.getQuestion(),
                optionResponses
        );
    }

    private List<String> buildAnswerOptions(Long lectureId, Flashcard currentCard, List<Flashcard> allCards) {
        LinkedHashSet<String> answers = new LinkedHashSet<>();

        String correctAnswer = normalizeAnswer(currentCard.getAnswer());
        answers.add(correctAnswer);

        for (Flashcard card : allCards) {
            if (!Objects.equals(card.getId(), currentCard.getId())) {
                String candidate = normalizeAnswer(card.getAnswer());
                if (!candidate.equalsIgnoreCase(correctAnswer)) {
                    answers.add(candidate);
                }
            }
        }

        List<String> fallback = List.of(
                "Недостаточно данных для ответа",
                "Не относится к данной лекции",
                "В лекции не указано",
                "Требуется дополнительная проверка"
        );

        for (String value : fallback) {
            if (answers.size() >= 4) {
                break;
            }
            if (!value.equalsIgnoreCase(correctAnswer)) {
                answers.add(value);
            }
        }

        List<String> result = new ArrayList<>(answers).subList(0, 4);

        long seed = Objects.hash(lectureId, currentCard.getId(), currentCard.getQuestion());
        Collections.shuffle(result, new Random(seed));

        return result;
    }

    private String findCorrectOptionId(QuizQuestionResponse question, String correctAnswer) {
        String normalizedCorrect = normalizeAnswer(correctAnswer);

        for (QuizOptionResponse option : question.getOptions()) {
            if (normalizeAnswer(option.getText()).equalsIgnoreCase(normalizedCorrect)) {
                return option.getId();
            }
        }

        return null;
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return "Ответ отсутствует";
        }

        return answer.trim().replaceAll("\\s+", " ");
    }

    private Lecture getLectureOwned(Long lectureId, String userEmail) {
        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Лекция не найдена"));

        if (lecture.getProject() == null
                || lecture.getProject().getOwner() == null
                || !Objects.equals(lecture.getProject().getOwner().getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        return lecture;
    }
}