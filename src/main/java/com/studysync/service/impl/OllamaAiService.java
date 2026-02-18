package com.studysync.service.impl;

import com.studysync.entity.dto.request.OllamaRequest;
import com.studysync.entity.dto.response.OllamaResponse;
import com.studysync.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class OllamaAiService implements AiService {

    private final RestClient restClient = RestClient.create("http://localhost:11434");

    @Override
    public String generate(String prompt) {

        OllamaRequest request = new OllamaRequest(
                "qwen2.5:1.5b",
                prompt,
                false
        );

        OllamaResponse response = restClient.post()
                .uri("/api/generate")
                .body(request)
                .retrieve()
                .body(OllamaResponse.class);

        return response.getResponse();
    }
}