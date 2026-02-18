package com.studysync.service.impl;

import com.studysync.service.TextCompressionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TextCompressionServiceImpl implements TextCompressionService {

    private static final int MIN_PARAGRAPH_LENGTH = 400;
    private static final double KEEP_RATIO = 0.15;
    private static final int MAX_COMPRESSED_LENGTH = 40000;

    @Override
    public String compress(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        String[] paragraphs = text.split("\\n\\s*\\n");

        List<ParagraphScore> scored = new ArrayList<>();

        for (String paragraph : paragraphs) {

            String cleaned = paragraph.trim();

            if (cleaned.length() < MIN_PARAGRAPH_LENGTH) {
                continue;
            }

            int length = cleaned.length();
            int uniqueWords = countUniqueWords(cleaned);

            long score = (long) length * uniqueWords;

            scored.add(new ParagraphScore(cleaned, score));
        }

        scored.sort((a, b) -> Long.compare(b.score, a.score));

        int limit = (int) (scored.size() * KEEP_RATIO);

        String result = scored.stream()
                .limit(limit)
                .map(p -> p.text)
                .collect(Collectors.joining("\n\n"));

        if (result.length() > MAX_COMPRESSED_LENGTH) {
            result = result.substring(0, MAX_COMPRESSED_LENGTH);
        }
        return result;
    }

    private int countUniqueWords(String text) {

        return Arrays.stream(text
                        .toLowerCase()
                        .replaceAll("[^а-яa-z0-9 ]", "")
                        .split("\\s+"))
                .filter(word -> word.length() > 3)
                .collect(Collectors.toSet())
                .size();
    }

    private static class ParagraphScore {
        String text;
        long score;

        ParagraphScore(String text, long score) {
            this.text = text;
            this.score = score;
        }
    }
}

