package com.studysync.entity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FlashcardResponse {
    private Long id;
    private String question;
    private String answer;
    private int sortOrder;
    private boolean userCreated;
    private boolean studied;
}