package com.studysync.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studysync.service.SummaryGenerationService;
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
public class OpenRouterSummaryGenerationServiceImpl implements SummaryGenerationService {

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

    private static final String SYSTEM_PROMPT = """
            Ты — помощник студента. Тебе дан текст лекции, распознанный с рукописных конспектов.

            Создай КРАТКОЕ РЕЗЮМЕ лекции на русском языке.

            ПРАВИЛА:
            - Выдели ключевые темы, определения, теоремы, формулы.
            - Резюме должно быть структурированным: используй абзацы, можно нумерованные пункты.
            - Объём: 150-400 слов (зависит от длины лекции).
            - Сохраняй математические формулы в LaTeX: inline $...$ и блочные $$...$$.
            - Если в тексте есть [?] (нераспознанные фрагменты) — пропускай их, не упоминай.
            - Пиши понятным языком, как конспект для подготовки к экзамену.
            - НЕ добавляй информацию, которой нет в лекции.
            - НЕ используй markdown-разметку (**, ##, - ). Пиши обычным текстом с нумерацией 1) 2) 3).
            - Отвечай ТОЛЬКО резюме. Без преамбул "Вот резюме:" и т.п.""";

    @Override
    public String generateSummary(String lectureText) {
        if (lectureText == null || lectureText.isBlank()) {
            return "Текст лекции пуст.";
        }

        long started = System.currentTimeMillis();

        try {
            // Убираем PAGE_BREAK разделители — модели они не нужны
            String cleanText = lectureText.replace("---PAGE_BREAK---", "\n\n");

            Map<String, Object> sysMsg = Map.of("role", "system", "content", SYSTEM_PROMPT);
            Map<String, Object> userMsg = Map.of("role", "user",
                    "content", "Вот текст лекции:\n\n" + cleanText);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(sysMsg, userMsg));
            body.put("temperature", 0.3);
            body.put("max_tokens", 2000);

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
            String summary = root.at("/choices/0/message/content").asText();

            // Очищаем от кодблоков если модель обернула
            summary = cleanResponse(summary);

            log.info("Summary generated. model={}, inputChars={}, outputChars={}, ms={}",
                    model, cleanText.length(), summary.length(),
                    System.currentTimeMillis() - started);

            return summary;

        } catch (Exception e) {
            log.error("Summary generation failed. model={}", model, e);
            throw new RuntimeException("Не удалось сгенерировать резюме", e);
        }
    }

    private String cleanResponse(String content) {
        if (content == null) return "";
        String s = content.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:text|markdown|plaintext|\\s)*", "");
            s = s.replaceFirst("\\s*```\\s*$", "");
            s = s.trim();
        }
        return s;
    }
}