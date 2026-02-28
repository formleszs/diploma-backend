package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class LectureListItemResponse {
    private Long id;
    private String title;
    private String status;
    private LocalDateTime createdAt;
}