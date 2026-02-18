package com.studysync.service;

import java.util.List;

public interface TextChunkingService {
    List<String> splitIntoChunks(String text, int chunkSize);
}
