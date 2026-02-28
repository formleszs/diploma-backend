package com.studysync.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studysync.entity.dto.response.TextCorrectionResponse;
import com.studysync.service.TextCorrectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.openrouter.enabled", havingValue = "true")
public class OpenRouterTextCorrectionServiceImpl implements TextCorrectionService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.builder().build();

    @Value("${ai.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${ai.openrouter.api-key:}")
    private String apiKey;

    @Value("${ai.openrouter.referer:http://localhost:5173}")
    private String referer;

    @Value("${ai.openrouter.title:StudySync}")
    private String title;

    @Value("${ai.openrouter.text-model:openai/gpt-4o-mini}")
    private String model;

    @Override
    public String correctRecognizedText(String text) {
        if (text == null || text.isBlank()) return "";

        try {
            Map<String, Object> body = buildRequest(text);

            String response = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("HTTP-Referer", referer)
                    .header("X-Title", title)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();
            content = content.replaceAll("(?m)^BEGIN_TEXT\\s*$", "")
                    .replaceAll("(?m)^END_TEXT\\s*$", "")
                    .trim();

            // ожидаем чистый JSON
            TextCorrectionResponse parsed = objectMapper.readValue(content, TextCorrectionResponse.class);
            return parsed.getCorrectedText() == null ? "" : parsed.getCorrectedText().trim();

        } catch (Exception e) {
            log.warn("Text correction failed, returning original text", e);
            return text;
        }
    }

    private Map<String, Object> buildRequest(String text) {

        String prompt = """
                Ты исправляешь ошибки распознавания текста (OCR/vision). Тебе дан исходный текст лекции.
                
                ВАЖНО: это коррекция распознавания, НЕ пересказ и НЕ “улучшение”.
                
                ЖЁСТКИЕ ПРАВИЛА:
                1) НЕ добавляй новый смысл. НЕ пересказывай. НЕ сокращай. НЕ улучшай стиль.
                2) Исправляй только очевидные ошибки распознавания (перепутанные буквы/слоги, явные опечатки).
                3) Если НЕ уверен — НЕ исправляй, лучше оставь как есть или замени сомнительный фрагмент на "[?]".
                4) НЕ изменяй и НЕ удаляй строки вида "=== PAGE X ===".
                5) Если в тексте есть LaTeX между $$...$$:
                   - сохраняй как есть, НЕ удаляй обратные слэши
                   - если внутри $$...$$ явный мусор/нечитаемо — замени только этот фрагмент на $$[?]$$
                6) Верни СТРОГО валидный JSON без ``` и без текста до/после.
                
                ФОРМАТ ОТВЕТА:
                {
                  "correctedText": "...",
                  "changes": [
                    {"from":"как было","to":"как стало","confidence":"high|medium","reason":"ocr_mistake"}
                  ]
                }
                
                Текст:
                BEGIN_TEXT
                %s
                END_TEXT
                """.formatted(text);

        Map<String, Object> msg = Map.of(
                "role", "user",
                "content", prompt
        );

        return new HashMap<>() {{
            put("model", model);
            put("messages", List.of(msg));
            put("temperature", 0);
            put("max_tokens", 10000);
        }};
    }
}