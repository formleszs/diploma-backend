package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
public class CreateProjectResponse {

    private Long id;
    private String name;
    private LocalDateTime createdAt;
}