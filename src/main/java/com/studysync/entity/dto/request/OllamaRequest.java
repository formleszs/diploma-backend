package com.studysync.entity.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OllamaRequest {

    private String model;
    private String prompt;
    private boolean stream;

}
