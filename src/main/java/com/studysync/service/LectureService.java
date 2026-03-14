package com.studysync.service;

import com.studysync.entity.dto.response.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface LectureService {

    LectureResponse createLectureWithUpload(Long projectId, String title, MultipartFile[] files, String userEmail);

    List<LectureListItemResponse> listProjectLectures(Long projectId, String userEmail);

    LectureResponse getLecture(Long lectureId, String userEmail);

    LectureDetailResponse getLectureDetail(Long lectureId, String userEmail);

    LectureTextResponse getLectureText(Long lectureId, String userEmail);

    LectureTextResponse updateLectureText(Long lectureId, String content, String userEmail);

    List<LectureFileResponse> getLectureFiles(Long lectureId, String userEmail);

    SummaryResponse generateLectureSummary(Long lectureId, String userEmail);

    QuizLockedResponse getLectureQuiz(Long lectureId, String userEmail);

    void deleteLecture(Long lectureId, String userEmail);
}