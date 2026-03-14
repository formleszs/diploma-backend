package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class LectureDetailResponse {
    private Long id;
    private Long projectId;
    private String title;
    private String status;
    private String content;
    private List<LectureFileResponse> files;
    private LocalDateTime createdAt;
}