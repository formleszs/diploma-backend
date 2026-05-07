package com.studysync.entity.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class QuizSubmitRequest {
    private List<QuizAnswerRequest> answers;
}