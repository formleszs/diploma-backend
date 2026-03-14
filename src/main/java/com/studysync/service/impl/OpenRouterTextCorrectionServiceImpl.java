package com.studysync.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studysync.entity.dto.response.TextCorrectionResponse;
import com.studysync.service.TextCorrectionService;
import com.studysync.util.JsonSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Vision-based коррекция OCR: модель получает И изображение И распознанный текст,
 * сравнивает их и исправляет ошибки.
 *
 * Это значительно точнее чем чисто текстовая коррекция, потому что модель
 * может смотреть на рукопись и проверять каждое слово.
 */
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

    // ============================================================
    //  Vision-based коррекция (основной метод)
    // ============================================================

    private static final String VISION_CORRECTION_SYSTEM = """
            Ты — корректор текста, распознанного с фотографий рукописных лекций.

            Тебе дано:
            1) Фотография страницы рукописной лекции
            2) Текст, который OCR-система распознала с этой фотографии

            ТВОЯ ЗАДАЧА: сверить текст с изображением и исправить ошибки распознавания.

            ПРАВИЛА:
            - Смотри на изображение и сравнивай с текстом слово за словом.
            - Если слово в тексте не совпадает с тем что написано на фото — исправь.
            - Если не можешь прочитать слово на фото — поставь [?].
            - НЕ добавляй текст, которого нет на фото. НЕ удаляй текст, который есть.
            - Сохраняй структуру: абзацы, списки, нумерацию.
            - Формулы:
              * Inline-формулы (в строке с текстом): $формула$
              * Блочные формулы (на отдельной строке, матрицы, крупные): $$формула$$
            - Верни ТОЛЬКО исправленный текст. Без пояснений, без комментариев, без кодблоков.""";

    @Override
    public String correctWithImage(String ocrText, Path imagePath) {
        if (ocrText == null || ocrText.isBlank()) return "";
        if (imagePath == null || !Files.exists(imagePath)) {
            log.warn("Image not found for vision correction, falling back to text-only");
            return correctRecognizedText(ocrText);
        }

        long started = System.currentTimeMillis();

        try {
            String mime = guessMime(imagePath);
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            String dataUrl = "data:" + mime + ";base64," + b64;

            Map<String, Object> sysMsg = Map.of("role", "system", "content", VISION_CORRECTION_SYSTEM);

            List<Map<String, Object>> userContent = new ArrayList<>();
            userContent.add(Map.of("type", "text",
                    "text", "Вот распознанный текст. Сверь его с изображением и исправь ошибки:\n\n" + ocrText));
            userContent.add(Map.of("type", "image_url",
                    "image_url", Map.of("url", dataUrl)));

            Map<String, Object> userMsg = Map.of("role", "user", "content", userContent);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(sysMsg, userMsg));
            body.put("temperature", 0);
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
            String corrected = root.at("/choices/0/message/content").asText();

            // Модель возвращает plain text (не JSON) — просто очищаем кодблоки
            corrected = cleanPlainText(corrected);

            log.info("Vision correction done. model={}, inChars={}, outChars={}, ms={}",
                    model, ocrText.length(), corrected.length(),
                    System.currentTimeMillis() - started);

            if (corrected.isBlank()) return ocrText;
            return corrected.trim();

        } catch (Exception e) {
            log.warn("Vision correction failed, falling back to text-only. model={}", model, e);
            return correctRecognizedText(ocrText);
        }
    }

    // ============================================================
    //  Text-only коррекция (fallback)
    // ============================================================

    private static final String TEXT_CORRECTION_SYSTEM = """
            Ты — корректор текста, распознанного с рукописных лекций. \
            Исправляй ошибки OCR по контексту. НЕ добавляй/удаляй текст. \
            Формулы: inline $...$ и блочные $$...$$. \
            Верни JSON: { "correctedText": "..." }. Без кодблоков.""";

    @Override
    public String correctRecognizedText(String text) {
        if (text == null || text.isBlank()) return "";

        long started = System.currentTimeMillis();

        try {
            Map<String, Object> sysMsg = Map.of("role", "system", "content", TEXT_CORRECTION_SYSTEM);
            Map<String, Object> userMsg = Map.of("role", "user",
                    "content", "Исправь ошибки распознавания:\n\n" + text);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(sysMsg, userMsg));
            body.put("temperature", 0);
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

            TextCorrectionResponse parsed = objectMapper.readValue(content, TextCorrectionResponse.class);
            String corrected = parsed.getCorrectedText();

            log.info("Text correction done. model={}, inChars={}, outChars={}, ms={}",
                    model, text.length(), corrected == null ? 0 : corrected.length(),
                    System.currentTimeMillis() - started);

            if (corrected == null || corrected.isBlank()) return text;
            return corrected.trim();

        } catch (Exception e) {
            log.warn("Text correction failed, returning original. model={}", model, e);
            return text;
        }
    }

    // ============================================================
    //  Utils
    // ============================================================

    private String cleanPlainText(String content) {
        if (content == null) return "";
        String s = content.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:text|markdown|plaintext|\\s)*", "");
            s = s.replaceFirst("\\s*```\\s*$", "");
            s = s.trim();
        }
        return s;
    }

    private String guessMime(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}