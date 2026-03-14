package com.studysync.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studysync.service.FlashcardGenerationService;
import com.studysync.util.JsonSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.openrouter.enabled", havingValue = "true")
public class OpenRouterFlashcardGenerationServiceImpl implements FlashcardGenerationService {

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

    // ============================================================
    //  Генерация набора карточек
    // ============================================================

    private static final String BATCH_SYSTEM = """
            Ты — помощник студента. Создай карточки для запоминания (flashcards) по тексту лекции.

            ПРАВИЛА:
            - Каждая карточка: короткий вопрос + краткий ответ.
            - Вопросы должны проверять понимание ключевых понятий, определений, формул, теорем.
            - Ответы — лаконичные (1-3 предложения), точные, по существу.
            - Количество карточек: 8-15 штук (зависит от объёма лекции).
            - Формулы в LaTeX: inline $...$ и блочные $$...$$.
            - НЕ используй markdown-разметку.
            - Язык: русский.

            ФОРМАТ ОТВЕТА — строго JSON:
            { "cards": [ { "q": "вопрос", "a": "ответ" }, ... ] }
            Без кодблоков. Без пояснений.""";

    @Override
    public List<String[]> generateFlashcards(String lectureText) {
        if (lectureText == null || lectureText.isBlank()) {
            return List.of();
        }

        long started = System.currentTimeMillis();

        try {
            String cleanText = lectureText.replace("---PAGE_BREAK---", "\n\n");

            Map<String, Object> sysMsg = Map.of("role", "system", "content", BATCH_SYSTEM);
            Map<String, Object> userMsg = Map.of("role", "user",
                    "content", "Создай карточки по этой лекции:\n\n" + cleanText);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(sysMsg, userMsg));
            body.put("temperature", 0.3);
            body.put("max_tokens", 4000);

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

            content = JsonSanitizer.sanitize(content);
            JsonNode parsed = objectMapper.readTree(content);
            JsonNode cards = parsed.get("cards");

            List<String[]> result = new ArrayList<>();
            if (cards != null && cards.isArray()) {
                for (JsonNode card : cards) {
                    String q = card.has("q") ? card.get("q").asText() : "";
                    String a = card.has("a") ? card.get("a").asText() : "";
                    if (!q.isBlank() && !a.isBlank()) {
                        result.add(new String[]{q, a});
                    }
                }
            }

            log.info("Flashcards generated. model={}, cards={}, ms={}",
                    model, result.size(), System.currentTimeMillis() - started);

            return result;

        } catch (Exception e) {
            log.error("Flashcard generation failed. model={}", model, e);
            throw new RuntimeException("Не удалось сгенерировать карточки", e);
        }
    }

    // ============================================================
    //  Генерация одной карточки по определению пользователя
    // ============================================================

    private static final String SINGLE_SYSTEM = """
            Ты — помощник студента. Создай ОДНУ карточку для запоминания по заданному определению/теме, \
            используя контекст лекции.

            ПРАВИЛА:
            - Вопрос: короткий, проверяет понимание данного определения/темы.
            - Ответ: лаконичный (1-3 предложения), точный.
            - Формулы в LaTeX: inline $...$ и блочные $$...$$.
            - НЕ используй markdown.

            ФОРМАТ: JSON
            { "q": "вопрос", "a": "ответ" }
            Без кодблоков.""";

    @Override
    public String[] generateSingleFlashcard(String definition, String lectureText) {
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("Определение не может быть пустым");
        }

        long started = System.currentTimeMillis();

        try {
            String cleanText = lectureText == null ? "" : lectureText.replace("---PAGE_BREAK---", "\n\n");

            String userContent = "Определение/тема: " + definition;
            if (!cleanText.isBlank()) {
                // Обрезаем контекст если слишком длинный
                String context = cleanText.length() > 3000 ? cleanText.substring(0, 3000) + "..." : cleanText;
                userContent += "\n\nКонтекст лекции:\n" + context;
            }

            Map<String, Object> sysMsg = Map.of("role", "system", "content", SINGLE_SYSTEM);
            Map<String, Object> userMsg = Map.of("role", "user", "content", userContent);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(sysMsg, userMsg));
            body.put("temperature", 0.3);
            body.put("max_tokens", 500);

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

            content = JsonSanitizer.sanitize(content);
            JsonNode parsed = objectMapper.readTree(content);

            String q = parsed.has("q") ? parsed.get("q").asText() : definition;
            String a = parsed.has("a") ? parsed.get("a").asText() : "";

            log.info("Single flashcard generated. model={}, ms={}",
                    model, System.currentTimeMillis() - started);

            return new String[]{q, a};

        } catch (Exception e) {
            log.error("Single flashcard generation failed. model={}", model, e);
            throw new RuntimeException("Не удалось сгенерировать карточку", e);
        }
    }
}