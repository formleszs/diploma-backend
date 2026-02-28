package com.studysync.service;

import java.io.File;

public interface PdfTextExtractionService {

    String extractText(File file);
    int countPages(File file);
}
