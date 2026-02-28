package com.studysync.controller;

import com.studysync.entity.dto.response.*;
import com.studysync.service.LectureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class LectureController {

    private final LectureService lectureService;

    // Создать лекцию + загрузить файлы (либо 1 PDF, либо до 10 фото)
    @PostMapping(value = "/api/projects/{projectId}/lectures", consumes = "multipart/form-data")
    public LectureResponse createLectureWithUpload(
            @PathVariable("projectId") Long projectId,
            @RequestParam("title") String title,
            @RequestParam("files") MultipartFile[] files,
            Authentication authentication
    ) {
        log.info("Получен запрос на добавление лекции в проекте {}",projectId);
        return lectureService.createLectureWithUpload(projectId, title, files, authentication.getName());
    }

    // Список лекций проекта
    @GetMapping("/api/projects/{projectId}/lectures")
    public List<LectureListItemResponse> listProjectLectures(
            @PathVariable("projectId") Long projectId,
            Authentication authentication
    ) {
        return lectureService.listProjectLectures(projectId, authentication.getName());
    }

    // Детали лекции
    @GetMapping("/api/lectures/{lectureId}")
    public LectureResponse getLecture(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLecture(lectureId, authentication.getName());
    }

    // Текст лекции (PDF уже будет, фото пока заглушка)
    @GetMapping("/api/lectures/{lectureId}/text")
    public LectureTextResponse getLectureText(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLectureText(lectureId, authentication.getName());
    }

    // Заглушки под UI
    @PostMapping("/api/lectures/{lectureId}/summary")
    public SummaryResponse summary(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.generateLectureSummary(lectureId, authentication.getName());
    }

    @PostMapping("/api/lectures/{lectureId}/flashcards")
    public FlashcardsResponse flashcards(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.generateLectureFlashcards(lectureId, authentication.getName());
    }

    @GetMapping("/api/lectures/{lectureId}/quiz")
    public QuizLockedResponse quiz(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLectureQuiz(lectureId, authentication.getName());
    }
}