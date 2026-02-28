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
    public String extractText(File pdfFile) {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            log.info("Extracted {} chars from PDF {}", (text == null ? 0 : text.length()), pdfFile.getName());
            return text == null ? "" : text;
        } catch (IOException e) {
            throw new RuntimeException("PDF text extraction failed", e);
        }
    }

    @Override
    public int countPages(File pdfFile) {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new RuntimeException("PDF page count failed", e);
        }
    }
}