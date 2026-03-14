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
import java.util.concurrent.*;

/**
 * Vision OCR через OpenRouter — per-page параллельный пайплайн.
 *
 * Ключевые решения:
 *
 * 1) PER-PAGE запросы: 1 изображение = 1 API-запрос.
 *    - Модель фокусируется на одной странице → качество выше.
 *    - Ответ короткий → не обрезается по max_tokens.
 *    - Запросы идут параллельно (до MAX_PARALLEL).
 *
 * 2) PLAIN TEXT ответ: модель возвращает текст, НЕ JSON.
 *    - Убираем 90% проблем с JSON-парсингом и LaTeX-экранированием.
 *    - VisionTranscriptionResponse формируем на бэке.
 *
 * 3) System + User messages: system задаёт роль OCR-транскрибатора,
 *    user — минимальная инструкция + изображение.
 *
 * 4) Per-page fallback: плохая страница (мало текста / много [?])
 *    повторяется только она с fallback-моделью.
 */
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

    @Value("${ai.openrouter.vision-model:google/gemini-2.5-flash-lite}")
    private String primaryModel;

    @Value("${ai.openrouter.vision-fallback-model:openai/gpt-4o}")
    private String fallbackModel;

    // --- Пороги качества ---
    private static final int PAGE_MIN_CHARS = 80;
    private static final int PAGE_MAX_UNCERTAIN = 10;
    private static final double PAGE_UNCERTAIN_RATIO = 0.05;

    // --- Параллельность ---
    private static final int MAX_PARALLEL = 4;
    private static final int PAGE_TIMEOUT_SEC = 60;

    // ============================================================
    //  Основной метод
    // ============================================================

    @Override
    public VisionTranscriptionResponse transcribeLectureImages(List<Path> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty()) {
            return emptyResponse();
        }

        long started = System.currentTimeMillis();
        int totalPages = imagePaths.size();

        // 1) Параллельный OCR всех страниц primary-моделью
        List<PageResult> results = transcribePages(primaryModel, imagePaths);

        log.info("Vision OCR primary done. model={}, pages={}, ms={}",
                primaryModel, totalPages, System.currentTimeMillis() - started);

        // 2) Находим "плохие" страницы
        List<Integer> badIdx = findBadPages(results);

        if (!badIdx.isEmpty()) {
            log.info("Vision OCR: {} bad pages {} -> fallback with {}",
                    badIdx.size(), badIdx, fallbackModel);

            List<Path> badPaths = badIdx.stream()
                    .map(imagePaths::get)
                    .toList();

            List<PageResult> fallbackResults = transcribePages(fallbackModel, badPaths);

            // 3) Мержим: заменяем плохие только если fallback лучше
            for (int i = 0; i < badIdx.size(); i++) {
                int idx = badIdx.get(i);
                PageResult fb = fallbackResults.get(i);
                PageResult pr = results.get(idx);

                if (isBetter(fb, pr)) {
                    results.set(idx, fb);
                    log.debug("Page {} replaced: primary(chars={}, unc={}) -> fallback(chars={}, unc={})",
                            idx + 1, pr.text.length(), pr.uncertainCount,
                            fb.text.length(), fb.uncertainCount);
                }
            }
        }

        // 4) Собираем ответ
        VisionTranscriptionResponse response = buildResponse(results);

        log.info("Vision OCR complete. pages={}, chars={}, uncertain={}, badFallback={}, ms={}",
                totalPages, response.getCharsTotal(), response.getUncertainCountTotal(),
                badIdx.size(), System.currentTimeMillis() - started);

        return response;
    }

    // ============================================================
    //  Параллельная обработка страниц
    // ============================================================

    private List<PageResult> transcribePages(String model, List<Path> paths) {
        if (paths.size() == 1) {
            return new ArrayList<>(List.of(transcribeSinglePage(model, paths.get(0), 1)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL, paths.size()));
        List<Future<PageResult>> futures = new ArrayList<>();

        for (int i = 0; i < paths.size(); i++) {
            final int pageNum = i + 1;
            final Path path = paths.get(i);
            futures.add(pool.submit(() -> transcribeSinglePage(model, path, pageNum)));
        }

        List<PageResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(PAGE_TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                log.warn("Page {} timed out (model={})", i + 1, model);
                results.add(PageResult.ofFailed());
            } catch (Exception e) {
                log.warn("Page {} error (model={}): {}", i + 1, model, e.getMessage());
                results.add(PageResult.ofFailed());
            }
        }

        pool.shutdown();
        return results;
    }

    private PageResult transcribeSinglePage(String model, Path imagePath, int pageNum) {
        try {
            String text = callApi(model, imagePath, pageNum);
            int uncertain = countOccurrences(text, "[?]");
            return new PageResult(text, uncertain, false);
        } catch (Exception e) {
            log.warn("OCR failed: page={}, model={}, err={}", pageNum, model, e.getMessage());
            return PageResult.ofFailed();
        }
    }

    // ============================================================
    //  API call — plain text
    // ============================================================

    private String callApi(String model, Path imagePath, int pageNum) throws Exception {
        String mime = guessMime(imagePath);
        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
        String dataUrl = "data:" + mime + ";base64," + b64;

        Map<String, Object> sysMsg = Map.of(
                "role", "system",
                "content", SYSTEM_PROMPT
        );

        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", "Страница " + pageNum + ". Перепиши весь текст:"));
        userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));

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
        String content = root.at("/choices/0/message/content").asText();

        return cleanResponse(content);
    }

    // ============================================================
    //  Промпт
    // ============================================================

    private static final String SYSTEM_PROMPT = """
        Ты — OCR-транскрибатор рукописных университетских лекций на РУССКОМ языке.

        КОНТЕКСТ: Тебе дают фотографии тетрадных страниц с рукописным текстом студента. Текст на русском языке, почерк может быть сложным (курсив, скоропись). Темы — учебные: лингвистика, математика, физика, история и т.д.

        ПРАВИЛА:
        - Переписывай ДОСЛОВНО то что видишь. НЕ перефразируй, НЕ исправляй грамматику.
        - Сохраняй структуру: заголовки, списки, нумерацию, абзацы.
        - Весь текст — на РУССКОМ ЯЗЫКЕ кириллицей. Не подставляй латинские буквы в русские слова.
        - Если видишь слово нечётко — постарайся прочитать по контексту (это лекция, слова должны быть осмысленными).
        - Если слово/фрагмент совсем НЕЧИТАЕМ — пиши [?]. Лучше [?] чем бессмысленный набор букв.
        - ФОРМУЛЫ — ВАЖНО:
          * Inline-формулы (внутри строки текста, рядом с обычными словами) → оборачивай в ОДИНАРНЫЙ доллар: $формула$
          * Блочные формулы (стоят отдельно на своей строке, обычно крупные: матрицы, дроби, системы уравнений) → оборачивай в ДВОЙНОЙ доллар: $$формула$$
          * Пример inline: "переменной $x$ соответствует значение $2$"
          * Пример inline: "предикаты $P(x, 2, y)$ и $P(z, z, 7)$"
          * Пример блочный (матрица на отдельной строке):
            $$\\Theta = \\begin{pmatrix} x & y & z \\\\ 2 & 7 & 2 \\end{pmatrix}$$
          * Греческие буквы — через LaTeX: $\\alpha$, $\\beta$, $\\Theta$
        - Отвечай ТОЛЬКО текстом лекции. Без комментариев, без заголовков типа "Страница 1", без кодблоков.""";

    // ============================================================
    //  Очистка ответа
    // ============================================================

    private String cleanResponse(String content) {
        if (content == null) return "";
        String s = content.trim();

        // Убираем кодблоки если модель обернула
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:text|markdown|plaintext|\\s)*", "");
            s = s.replaceFirst("\\s*```\\s*$", "");
            s = s.trim();
        }

        return s;
    }

    // ============================================================
    //  Quality assessment
    // ============================================================

    private List<Integer> findBadPages(List<PageResult> results) {
        List<Integer> bad = new ArrayList<>();

        for (int i = 0; i < results.size(); i++) {
            PageResult r = results.get(i);

            if (r.failed) {
                bad.add(i);
                continue;
            }

            int len = r.text.length();
            int unc = r.uncertainCount;

            // Слишком мало текста
            if (len < PAGE_MIN_CHARS) {
                bad.add(i);
                continue;
            }

            // Много [?]
            if (unc > PAGE_MAX_UNCERTAIN) {
                bad.add(i);
                continue;
            }

            // Высокая доля [?]
            if (len > 0 && (double)(unc * 3) / len > PAGE_UNCERTAIN_RATIO) {
                bad.add(i);
                continue;
            }

            // Эвристика "мусорного текста":
            // Дешёвые модели часто не ставят [?], а уверенно пишут бессмыслицу.
            // Признаки: много слов без гласных, много коротких бессмысленных "слов",
            // высокая плотность редких сочетаний букв.
            if (looksLikeGarbage(r.text)) {
                log.debug("Page {} flagged as garbage by heuristic", i + 1);
                bad.add(i);
            }
        }

        return bad;
    }

    /**
     * Эвристика: текст выглядит как мусор (модель "уверенно" написала бессмыслицу).
     *
     * Проверяем:
     * 1) Доля "плохих" слов (без гласных, или > 70% согласных, или нераспознаваемые)
     * 2) Среднюю длину слов (мусор часто имеет очень длинные или очень короткие "слова")
     */
    private boolean looksLikeGarbage(String text) {
        if (text == null || text.length() < 100) return false;

        // Разбиваем на слова (кириллица)
        String[] words = text.split("[^а-яА-ЯёЁ]+");

        int totalWords = 0;
        int badWords = 0;

        for (String w : words) {
            if (w.length() < 2) continue;
            totalWords++;

            String lower = w.toLowerCase();

            // Слово без единой гласной — почти наверняка мусор
            if (!lower.matches(".*[аеёиоуыэюя].*")) {
                badWords++;
                continue;
            }

            // Слишком длинное слово (>25 символов) без пробелов — склеенный мусор
            if (lower.length() > 25) {
                badWords++;
                continue;
            }

            // Высокая плотность согласных: > 75% букв — согласные
            long consonants = lower.chars()
                    .filter(c -> "бвгджзклмнпрстфхцчшщ".indexOf(c) >= 0)
                    .count();
            if (lower.length() >= 4 && (double) consonants / lower.length() > 0.75) {
                badWords++;
            }
        }

        if (totalWords < 5) return false;

        double badRatio = (double) badWords / totalWords;

        // Если > 30% слов выглядят как мусор — страница плохая
        return badRatio > 0.30;
    }

    private boolean isBetter(PageResult candidate, PageResult current) {
        if (current.failed && !candidate.failed) return true;
        if (candidate.failed) return false;

        int curScore = current.text.length() - current.uncertainCount * 50;
        int canScore = candidate.text.length() - candidate.uncertainCount * 50;
        return canScore > curScore;
    }

    // ============================================================
    //  Build response
    // ============================================================

    private VisionTranscriptionResponse buildResponse(List<PageResult> results) {
        List<VisionTranscriptionResponse.Page> pages = new ArrayList<>();
        int totalChars = 0;
        int totalUncertain = 0;

        for (int i = 0; i < results.size(); i++) {
            PageResult r = results.get(i);

            VisionTranscriptionResponse.Page page = new VisionTranscriptionResponse.Page();
            page.setIndex(i + 1);
            page.setText(r.text);
            page.setUncertainCount(r.uncertainCount);

            pages.add(page);
            totalChars += r.text.length();
            totalUncertain += r.uncertainCount;
        }

        VisionTranscriptionResponse resp = new VisionTranscriptionResponse();
        resp.setPages(pages);
        resp.setCharsTotal(totalChars);
        resp.setUncertainCountTotal(totalUncertain);
        return resp;
    }

    private VisionTranscriptionResponse emptyResponse() {
        VisionTranscriptionResponse r = new VisionTranscriptionResponse();
        r.setPages(List.of());
        r.setCharsTotal(0);
        r.setUncertainCountTotal(0);
        return r;
    }

    // ============================================================
    //  Utils
    // ============================================================

    private int countOccurrences(String s, String needle) {
        if (s == null || s.isEmpty() || needle == null || needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = s.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String guessMime(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static class PageResult {
        final String text;
        final int uncertainCount;
        final boolean failed;

        PageResult(String text, int uncertainCount, boolean failed) {
            this.text = text;
            this.uncertainCount = uncertainCount;
            this.failed = failed;
        }

        static PageResult ofFailed() {
            return new PageResult("", 0, true);
        }
    }
}