package com.studysync.service.impl;

import com.studysync.service.TextChunkingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TextChunkingServiceImpl implements TextChunkingService {

    @Override
    public List<String> splitIntoChunks(String text, int chunkSize) {

        List<String> chunks = new ArrayList<>();

        int start = 0;

        while (start < text.length()) {

            int end = Math.min(start + chunkSize, text.length());

            // стараемся не резать посреди абзаца
            if (end < text.length()) {
                int lastNewLine = text.lastIndexOf("\n", end);
                if (lastNewLine > start) {
                    end = lastNewLine;
                }
            }

            String chunk = text.substring(start, end).trim();
            chunks.add(chunk);

            start = end;
        }

        return chunks;
    }
}
