package com.studysync.service.impl;

import com.studysync.service.PdfTextExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Slf4j
@Service
public class PdfTextExtractionServiceImpl implements PdfTextExtractionService {

    @Override
    public String extractText(File file) {

        try (PDDocument document = PDDocument.load(file)) {

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            log.info("Extracted {} characters from PDF {}", text.length(), file.getName());

            return text;

        } catch (IOException e) {
            log.error("Failed to extract text from PDF {}", file.getName(), e);
            throw new RuntimeException("PDF text extraction failed", e);
        }
    }
}
