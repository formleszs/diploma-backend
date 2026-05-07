package com.studysync.service;

import com.studysync.entity.dto.request.QuizSubmitRequest;
import com.studysync.entity.dto.response.QuizResponse;
import com.studysync.entity.dto.response.QuizSubmitResponse;

public interface QuizService {

    QuizResponse getQuiz(Long lectureId, String userEmail);

    QuizSubmitResponse submitQuiz(Long lectureId, QuizSubmitRequest request, String userEmail);
}