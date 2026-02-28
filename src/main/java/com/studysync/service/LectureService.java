package com.studysync.service;

import com.studysync.entity.dto.response.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface LectureService {

    LectureResponse createLectureWithUpload(Long projectId, String title, MultipartFile[] files, String userEmail);

    List<LectureListItemResponse> listProjectLectures(Long projectId, String userEmail);

    LectureResponse getLecture(Long lectureId, String userEmail);

    LectureTextResponse getLectureText(Long lectureId, String userEmail);

    SummaryResponse generateLectureSummary(Long lectureId, String userEmail);

    FlashcardsResponse generateLectureFlashcards(Long lectureId, String userEmail);

    QuizLockedResponse getLectureQuiz(Long lectureId, String userEmail);
}