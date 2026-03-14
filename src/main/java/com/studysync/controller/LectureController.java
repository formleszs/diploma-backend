package com.studysync.controller;

import com.studysync.entity.dto.request.UpdateLectureTextRequest;
import com.studysync.entity.dto.response.*;
import com.studysync.service.LectureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class LectureController {

    private final LectureService lectureService;

    @PostMapping("/api/projects/{projectId}/lectures")
    public LectureResponse createLecture(
            @PathVariable("projectId") Long projectId,
            @RequestParam("title") String title,
            @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files,
            Authentication authentication
    ) {
        return lectureService.createLectureWithUpload(projectId, title, files, authentication.getName());
    }

    @GetMapping("/api/projects/{projectId}/lectures")
    public List<LectureListItemResponse> listProjectLectures(
            @PathVariable("projectId") Long projectId,
            Authentication authentication
    ) {
        return lectureService.listProjectLectures(projectId, authentication.getName());
    }

    @GetMapping("/api/lectures/{lectureId}")
    public LectureResponse getLecture(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLecture(lectureId, authentication.getName());
    }

    /**
     * Полная информация о лекции: текст + файлы (для страницы просмотра/редактирования).
     */
    @GetMapping("/api/lectures/{lectureId}/detail")
    public LectureDetailResponse getLectureDetail(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLectureDetail(lectureId, authentication.getName());
    }

    @GetMapping("/api/lectures/{lectureId}/text")
    public LectureTextResponse getLectureText(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLectureText(lectureId, authentication.getName());
    }

    /**
     * Сохранение отредактированного текста лекции.
     */
    @PutMapping("/api/lectures/{lectureId}/text")
    public LectureTextResponse updateLectureText(
            @PathVariable("lectureId") Long lectureId,
            @RequestBody UpdateLectureTextRequest request,
            Authentication authentication
    ) {
        return lectureService.updateLectureText(lectureId, request.getContent(), authentication.getName());
    }

    /**
     * Список файлов (фото) лекции.
     */
    @GetMapping("/api/lectures/{lectureId}/files")
    public List<LectureFileResponse> getLectureFiles(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLectureFiles(lectureId, authentication.getName());
    }

    // Заглушки под UI
    @GetMapping("/api/lectures/{lectureId}/summary")
    public SummaryResponse summary(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.generateLectureSummary(lectureId, authentication.getName());
    }

    @GetMapping("/api/lectures/{lectureId}/quiz")
    public QuizLockedResponse quiz(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        return lectureService.getLectureQuiz(lectureId, authentication.getName());
    }

    @DeleteMapping("/api/lectures/{lectureId}")
    public ResponseEntity<Void> deleteLecture(
            @PathVariable("lectureId") Long lectureId,
            Authentication authentication
    ) {
        lectureService.deleteLecture(lectureId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}