package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class LectureResponse {
    private Long id;
    private Long projectId;
    private String title;
    private String status;
    private int filesCount;
    private LocalDateTime createdAt;
}