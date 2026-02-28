package com.studysync.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studysync.entity.dto.response.VisionTranscriptionResponse;
import com.studysync.service.VisionTranscriptionService;
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

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.openrouter.enabled", havingValue = "true")
public class OpenRouterVisionTranscriptionServiceImpl implements VisionTranscriptionService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder().build();

    @Value("${ai.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${ai.openrouter.api-key}")
    private String apiKey;

    @Value("${ai.openrouter.referer:http://localhost:5173}")
    private String referer;

    @Value("${ai.openrouter.title:StudySync}")
    private String title;

    @Value("${ai.openrouter.vision-model:openai/gpt-4o-mini}")
    private String model;

    @Value("${ai.openrouter.vision-fallback-model:openai/gpt-4o}")
    private String fallbackModel;

    @Override
    public VisionTranscriptionResponse transcribeLectureImages(List<Path> imagePaths) {

        VisionTranscriptionResponse res = call(model, imagePaths);

        // простая авто-проверка: если слишком много [?], делаем фолбэк
        if (res.getUncertainCountTotal() > 40 || res.getCharsTotal() < 1500) {
            log.warn("Low transcription quality detected (uncertain={}, chars={}). Retrying with fallback model {}",
                    res.getUncertainCountTotal(), res.getCharsTotal(), fallbackModel);
            res = call(fallbackModel, imagePaths);
        }

        return res;
    }

    private String sanitizeJson(String s) {
        if (s == null) return "";
        s = s.trim();

        // убираем ```json ... ``` или ``` ... ```
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "");
            s = s.replaceFirst("\\s*```\\s*$", "");
            s = s.trim();
        }

        // если вокруг лишний текст — вырезаем первый {...} блок
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }

        return s.trim();
    }

    private int countOccurrences(String s, String needle) {
        if (s == null || s.isEmpty() || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String fixInvalidJsonEscapes(String s) {
        if (s == null) return "";
        // Чиним только ОДИНОЧНЫЙ "\" (не предшествующий "\")
        // и только если это НЕ валидный JSON-escape.
        return s.replaceAll("(?<!\\\\)\\\\(?![\"\\\\/bfnrtu])", "\\\\\\\\");
    }

    private VisionTranscriptionResponse call(String modelName, List<Path> imagePaths) {
        try {
            Map<String, Object> body = buildRequest(modelName, imagePaths);

            String response = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("HTTP-Referer", referer) // опционально, для attribution :contentReference[oaicite:3]{index=3}
                    .header("X-Title", title)        // опционально, для attribution :contentReference[oaicite:4]{index=4}
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();

            content = sanitizeJson(content);
            content = fixInvalidJsonEscapes(content);
            // Модель должна вернуть чистый JSON. Парсим его.
            VisionTranscriptionResponse parsed = objectMapper.readValue(content, VisionTranscriptionResponse.class);

            int chars = 0;
            int uncertain = 0;

            if (parsed.getPages() != null) {
                for (VisionTranscriptionResponse.Page p : parsed.getPages()) {
                    String t = p.getText() == null ? "" : p.getText();

                    chars += t.length();

                    int pageUncertain = countOccurrences(t, "[?]");
                    p.setUncertainCount(pageUncertain);

                    uncertain += pageUncertain;
                }
            }

            parsed.setCharsTotal(chars);
            parsed.setUncertainCountTotal(uncertain);

            return parsed;

        } catch (Exception e) {
            throw new RuntimeException("OpenRouter vision transcription failed", e);
        }
    }

    private Map<String, Object> buildRequest(String modelName, List<Path> imagePaths) throws Exception {

        List<Map<String, Object>> content = new ArrayList<>();

        // Текст первым, как рекомендует OpenRouter :contentReference[oaicite:5]{index=5}
        content.add(Map.of(
                "type", "text",
                "text", buildPrompt(imagePaths.size())
        ));

        int idx = 1;
        for (Path p : imagePaths) {
            String mime = guessMime(p);
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(p));
            String dataUrl = "data:" + mime + ";base64," + b64;

            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl)
            ));

            idx++;
        }

        Map<String, Object> message = Map.of(
                "role", "user",
                "content", content
        );

        return new HashMap<>() {{
            put("model", modelName);
            put("messages", List.of(message));
            put("temperature", 0);
            put("max_tokens", 10000); // чтобы не разносило
        }};
    }

    private String buildPrompt(int pages) {
        return """
                Ты выполняешь РАСПОЗНАВАНИЕ рукописного/печатного текста с изображений (НЕ пересказ, НЕ редактирование).
                
                Изображения идут по порядку страниц (всего страниц: %d). Каждое изображение = одна страница.
                
                ЖЁСТКИЕ ПРАВИЛА:
                1) Верни СТРОГО валидный JSON. Никаких ``` кодблоков. Никакого текста до/после.
                2) Переписывай ТОЛЬКО то, что видишь. НЕ перефразируй. НЕ исправляй грамматику. НЕ добавляй “логичные” слова.
                3) Сохраняй структуру: заголовки, списки, нумерацию.
                4) Если слово/символ/фрагмент НЕЧИТАЕМ или ТЫ НЕ УВЕРЕН — вставляй "[?]" прямо в text. НЕ угадывай.
                5) Если встречаются формулы/символы/матрицы — записывай их в LaTeX между $$ ... $$.
                6) КРИТИЧНО ДЛЯ JSON:
                   - Внутри "text" используй ТОЛЬКО экранированные переносы строк: \\n (не реальные переводы строки).
                   - Любой обратный слэш в LaTeX ОБЯЗАТЕЛЬНО удваивай: пиши \\\\lambda, \\\\frac, \\\\begin и т.д.
                   - То есть в JSON один символ "\" должен выглядеть как "\\".
                
                ФОРМАТ ОТВЕТА:
                {
                  "pages": [
                    { "index": 1, "text": "строка1\\nстрока2\\n... $$\\\\lambda$$ ..." },
                    { "index": 2, "text": "..." }
                  ]
                }
                """.formatted(pages);
    }

    private String guessMime(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}