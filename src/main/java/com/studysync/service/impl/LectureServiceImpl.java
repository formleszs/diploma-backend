package com.studysync.service.impl;

import com.studysync.entity.*;
import com.studysync.entity.dto.response.*;
import com.studysync.enums.LectureStatus;
import com.studysync.repository.*;
import com.studysync.service.LectureService;
import com.studysync.service.PdfTextExtractionService;
import com.studysync.service.TextCorrectionService;
import com.studysync.service.VisionTranscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LectureServiceImpl implements LectureService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    private final LectureRepository lectureRepository;
    private final LectureFileRepository lectureFileRepository;
    private final LectureTextRepository lectureTextRepository;

    private final PdfTextExtractionService pdfTextExtractionService;
    private final VisionTranscriptionService visionTranscriptionService;
    private final TextCorrectionService textCorrectionService;

    @Value("${app.upload.lecture-dir:./uploads/lectures}")
    private String lectureUploadDir;

    private static final int MAX_IMAGES = 10;
    private static final int MAX_PDF_PAGES = 10;

    private static final long MAX_PDF_SIZE = 20L * 1024 * 1024;
    private static final long MAX_IMAGE_SIZE = 6L * 1024 * 1024;

    private String normalizeRecognizedText(String s) {
        if (s == null) return "";

        // CRLF -> LF
        s = s.replace("\r\n", "\n");

        // Склеиваем переносы слов: "осно-\nвана" -> "основана" (кириллица)
        s = s.replaceAll("(?<=\\p{IsCyrillic})-\\s*\\n\\s*(?=\\p{IsCyrillic})", "");

        // Трим пробелы в конце строк
        s = s.replaceAll("[ \\t]+\\n", "\n");

        // Сжимаем слишком много пустых строк
        s = s.replaceAll("\\n{3,}", "\n\n");

        return s.trim();
    }

    private int countUncertain(String s) {
        if (s == null || s.isBlank()) return 0;
        int count = 0, idx = 0;
        while ((idx = s.indexOf("[?]", idx)) != -1) {
            count++;
            idx += 3;
        }
        return count;
    }


    @Override
    @Transactional
    public LectureResponse createLectureWithUpload(Long projectId, String title, MultipartFile[] files, String userEmail) {

        if (title == null || title.isBlank()) {
            log.error("Lecture title is required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lecture title is required");
        }
        if (files == null || files.length == 0) {
            log.error("Files are required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Files are required");
        }

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (project.getOwner() == null || !Objects.equals(project.getOwner().getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        UploadKind kind = validateLectureFiles(files);

        Lecture lecture = new Lecture();
        lecture.setProject(project);
        lecture.setTitle(title.trim());
        lecture.setStatus(LectureStatus.PROCESSING);
        lecture.setCreatedAt(LocalDateTime.now());

        lecture = lectureRepository.save(lecture);
        log.info("Lecture {} was saved for user {}", lecture.getTitle(),user.getId());
        Path lectureDir = Path.of(lectureUploadDir, String.valueOf(lecture.getId()));
        try {
            Files.createDirectories(lectureDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create lecture directory");
        }

        // сохраняем файлы
        List<LectureFile> savedFiles = new ArrayList<>();
        for (MultipartFile f : files) {
            LectureFile lf = storeLectureFile(lecture, lectureDir, f);
            savedFiles.add(lf);
        }

        // строим текст (PDF реально извлекаем, фото = заглушка)
        String content;
        LectureStatus finalStatus;

        if (kind == UploadKind.PDF) {
            File pdf = lectureDir.resolve(savedFiles.get(0).getStoredName()).toFile();
            int pages = countPdfPages(pdf);
            if (pages > MAX_PDF_PAGES) {
                lecture.setStatus(LectureStatus.FAILED);
                lectureRepository.save(lecture);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF max pages is 10");
            }

            content = pdfTextExtractionService.extractText(pdf);
            finalStatus = LectureStatus.PROCESSED;

        } else {
            // IMAGES: распознаём через OpenRouter vision
            final Long lectureId = lecture.getId();
            List<Path> imagePaths = savedFiles.stream()
                    .map(f -> Path.of(lectureUploadDir, String.valueOf(lectureId), f.getStoredName()))
                    .toList();

            VisionTranscriptionResponse tr = visionTranscriptionService.transcribeLectureImages(imagePaths);

            // 1) Собираем сырой текст со страниц
            StringBuilder rawAll = new StringBuilder();
            if (tr.getPages() != null) {
                for (var p : tr.getPages()) {
                    rawAll.append("\n\n=== PAGE ").append(p.getIndex()).append(" ===\n\n");
                    rawAll.append(p.getText() == null ? "" : p.getText());
                }
            }

            String normalized = normalizeRecognizedText(rawAll.toString());

            // 2) ОДИН запрос на коррекцию всего текста
            String corrected = textCorrectionService.correctRecognizedText(normalized);

            // 3) Финал
            content = corrected.isBlank() ? normalized : corrected;
            finalStatus = LectureStatus.PROCESSED;

            log.info("Lecture {} corrected.", lecture.getId());
        }

        LectureText lectureText = new LectureText();
        lectureText.setLecture(lecture);
        lectureText.setContent(content);
        lectureText.setCreatedAt(LocalDateTime.now());
        lectureTextRepository.save(lectureText);

        lecture.setStatus(finalStatus);
        lectureRepository.save(lecture);

        log.info("Lecture {} created for project {} by {}, status={}",
                lecture.getId(), projectId, userEmail, lecture.getStatus());

        return toLectureResponse(lecture);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LectureListItemResponse> listProjectLectures(Long projectId, String userEmail) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (project.getOwner() == null || !Objects.equals(project.getOwner().getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        return lectureRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(l -> new LectureListItemResponse(l.getId(), l.getTitle(), l.getStatus().name(), l.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LectureResponse getLecture(Long lectureId, String userEmail) {
        Lecture lecture = getLectureOwned(lectureId, userEmail);
        return toLectureResponse(lecture);
    }

    @Override
    @Transactional(readOnly = true)
    public LectureTextResponse getLectureText(Long lectureId, String userEmail) {

        getLectureOwned(lectureId, userEmail);

        LectureText txt = lectureTextRepository.findByLectureId(lectureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture text not found"));

        String content = txt.getContent() == null ? "" : txt.getContent();
        return new LectureTextResponse(lectureId, content.length(), content);
    }

    // Заглушки под UI: пока не подключали OpenRouter и не делали карточки/прогресс.
    @Override
    @Transactional(readOnly = true)
    public SummaryResponse generateLectureSummary(Long lectureId, String userEmail) {
        Lecture lecture = getLectureOwned(lectureId, userEmail);

        if (lecture.getStatus() != LectureStatus.PROCESSED) {
            return new SummaryResponse(lectureId, "Саммари недоступно: лекция ещё не обработана (OCR/Vision пока заглушка).");
        }

        return new SummaryResponse(lectureId, "Заглушка: саммари будет генерироваться позже.");
    }

    @Override
    @Transactional(readOnly = true)
    public FlashcardsResponse generateLectureFlashcards(Long lectureId, String userEmail) {
        Lecture lecture = getLectureOwned(lectureId, userEmail);

        if (lecture.getStatus() != LectureStatus.PROCESSED) {
            return new FlashcardsResponse(lectureId, "{\"flashcards\":[],\"message\":\"Недоступно: лекция не обработана\"}");
        }

        return new FlashcardsResponse(lectureId, "{\"flashcards\":[],\"message\":\"Заглушка: карточки будут позже\"}");
    }

    @Override
    @Transactional(readOnly = true)
    public QuizLockedResponse getLectureQuiz(Long lectureId, String userEmail) {
        getLectureOwned(lectureId, userEmail);
        return new QuizLockedResponse(false, "Тест заблокирован. Сначала нужно изучить все карточки (прогресс ещё не реализован).");
    }

    // -------------------- helpers --------------------

    private Lecture getLectureOwned(Long lectureId, String userEmail) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture not found"));

        if (lecture.getProject() == null || lecture.getProject().getOwner() == null ||
                !Objects.equals(lecture.getProject().getOwner().getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        return lecture;
    }

    private LectureFile storeLectureFile(Lecture lecture, Path lectureDir, MultipartFile f) {

        try {
            String ext = getExtension(f.getOriginalFilename());
            String storedName = UUID.randomUUID() + ext;

            Path filePath = lectureDir.resolve(storedName);
            Files.copy(f.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            LectureFile lf = new LectureFile();
            lf.setLecture(lecture);
            lf.setOriginalName(f.getOriginalFilename());
            lf.setStoredName(storedName);
            lf.setContentType(f.getContentType());
            lf.setSize(f.getSize());

            return lectureFileRepository.save(lf);

        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
        }
    }

    private UploadKind validateLectureFiles(MultipartFile[] files) {

        int pdfCount = 0;
        int imageCount = 0;

        for (MultipartFile f : files) {
            if (f.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
            }

            String ct = f.getContentType() == null ? "" : f.getContentType();

            if ("application/pdf".equals(ct)) {
                pdfCount++;
                if (f.getSize() > MAX_PDF_SIZE) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF max size is 20MB");
                }
            } else if (ct.startsWith("image/")) {
                imageCount++;
                if (f.getSize() > MAX_IMAGE_SIZE) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each image max size is 6MB");
                }
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type: " + ct);
            }
        }

        if (pdfCount > 0) {
            if (pdfCount != 1 || files.length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lecture supports ONLY one PDF file");
            }
            return UploadKind.PDF;
        }

        if (imageCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No images provided");
        }
        if (imageCount > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max 10 images per lecture");
        }

        return UploadKind.IMAGES;
    }

    private int countPdfPages(File pdfFile) {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid PDF");
        }
    }

    private LectureResponse toLectureResponse(Lecture lecture) {
        int count = (int) lectureFileRepository.countByLectureId(lecture.getId());
        return new LectureResponse(
                lecture.getId(),
                lecture.getProject().getId(),
                lecture.getTitle(),
                lecture.getStatus().name(),
                count,
                lecture.getCreatedAt()
        );
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i) : "";
    }

    private enum UploadKind { PDF, IMAGES }
}