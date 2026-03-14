package com.studysync.service.impl;

import com.studysync.entity.*;
import com.studysync.entity.dto.response.*;
import com.studysync.enums.LectureStatus;
import com.studysync.repository.*;
import com.studysync.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.studysync.entity.dto.response.LectureDetailResponse;
import com.studysync.entity.dto.response.LectureFileResponse;
import java.util.Optional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final ImagePreprocessorService imagePreprocessorService;

    private final LectureSummaryRepository lectureSummaryRepository;
    private final SummaryGenerationService summaryGenerationService;

    @Value("${app.upload.lecture-dir:./uploads/lectures}")
    private String lectureUploadDir;

    private static final int MAX_IMAGES = 10;
    private static final int MAX_PDF_PAGES = 10;

    private static final long MAX_PDF_SIZE = 20L * 1024 * 1024;
    private static final long MAX_IMAGE_SIZE = 6L * 1024 * 1024;

    private static final int PDF_MIN_TEXT_CHARS = 800;

    private static final int CORRECTION_MIN_CHARS = 1200;
    private static final int CORRECTION_UNCERTAIN_TOTAL = 35;
    private static final double CORRECTION_UNCERTAIN_PER_1K = 8.0;
    private static final String PAGE_BREAK = "\n---PAGE_BREAK---\n";

    private String normalizeRecognizedText(String s) {
        if (s == null) return "";

        s = s.replace("\r\n", "\n");
        s = s.replaceAll("(?<=\\p{IsCyrillic})-\\s*\\n\\s*(?=\\p{IsCyrillic})", "");
        s = s.replaceAll("[ \\t]+\\n", "\n");
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
        log.info("Lecture {} was saved for user {}", lecture.getTitle(), user.getId());

        Path lectureDir = Path.of(lectureUploadDir, String.valueOf(lecture.getId()));
        try {
            Files.createDirectories(lectureDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create lecture directory");
        }

        List<LectureFile> savedFiles = new ArrayList<>();
        for (MultipartFile f : files) {
            LectureFile lf = storeLectureFile(lecture, lectureDir, f);
            savedFiles.add(lf);
        }

        ProcessResult res = (kind == UploadKind.PDF)
                ? processPdf(lectureDir, savedFiles, lecture)
                : processImages(lecture.getId(), savedFiles);

        String content = res.content();
        LectureStatus finalStatus = res.status();

        // Если обработка упала, не делаем вид, что текст есть.
        if (finalStatus == LectureStatus.PROCESSED) {
            LectureText lectureText = new LectureText();
            lectureText.setLecture(lecture);
            lectureText.setContent(content);
            lectureText.setCreatedAt(LocalDateTime.now());
            lectureTextRepository.save(lectureText);
            log.info("Lecture {}: LectureText saved. chars={}", lecture.getId(), content == null ? 0 : content.length());
        } else {
            log.warn("Lecture {}: processing failed. No LectureText saved.", lecture.getId());
        }

        lecture.setStatus(finalStatus);
        lectureRepository.save(lecture);

        log.info("Lecture {} created for project {} by {}, status={}",
                lecture.getId(), projectId, userEmail, lecture.getStatus());

        return toLectureResponse(lecture);
    }

    private record ProcessResult(String content, LectureStatus status) {}

    private ProcessResult processPdf(Path lectureDir, List<LectureFile> savedFiles, Lecture lecture) {
        long started = System.currentTimeMillis();
        Long lectureId = lecture.getId();

        File pdf = lectureDir.resolve(savedFiles.get(0).getStoredName()).toFile();
        int pages = countPdfPages(pdf);
        if (pages > MAX_PDF_PAGES) {
            log.warn("Lecture {}: PDF has too many pages: {}", lectureId, pages);
            return new ProcessResult("", LectureStatus.FAILED);
        }

        String extracted = "";
        try {
            extracted = pdfTextExtractionService.extractText(pdf);
        } catch (Exception e) {
            log.warn("Lecture {}: PDF text extraction failed: {}", lectureId, e.toString());
        }
        extracted = normalizeRecognizedText(extracted);

        // Если PDF текстовый, живём счастливо. Если скан/фото, делаем OCR по страницам.
        if (extracted.length() >= PDF_MIN_TEXT_CHARS) {
            log.info("Lecture {}: PDF extracted chars={} (no OCR). ms={}",
                    lectureId, extracted.length(), (System.currentTimeMillis() - started));
            return new ProcessResult(extracted, LectureStatus.PROCESSED);
        }

        log.info("Lecture {}: PDF extracted chars={} -> fallback to Vision OCR (likely scanned PDF)",
                lectureId, extracted.length());

        try {
            Path pdfPagesDir = lectureDir.resolve("pdf_pages");
            Files.createDirectories(pdfPagesDir);

            List<Path> rendered = renderPdfToImages(pdf, pdfPagesDir);

            Path preDir = lectureDir.resolve("preprocessed");
            Files.createDirectories(preDir);

            List<Path> inputs = new ArrayList<>(rendered.size());
            int preCount = 0;
            for (int i = 0; i < rendered.size(); i++) {
                Path out = imagePreprocessorService.preprocess(rendered.get(i), preDir, i);
                inputs.add(out);
                if (out.startsWith(preDir)) preCount++;
            }
            log.info("Lecture {}: PDF render+preprocess done. pages={}, preprocessedUsed={}",
                    lectureId, inputs.size(), preCount);

            VisionTranscriptionResponse tr = visionTranscriptionService.transcribeLectureImages(inputs);
            String merged = mergePages(tr);
            String normalized = normalizeRecognizedText(merged);

            if (shouldRunCorrection(tr, normalized)) {
                log.info("Lecture {}: correction start. chars={}, uncertain={}",
                        lectureId, tr.getCharsTotal(), tr.getUncertainCountTotal());
                String corrected = textCorrectionService.correctRecognizedText(normalized);
                normalized = (corrected == null || corrected.isBlank()) ? normalized : corrected;
                log.info("Lecture {}: correction done.", lectureId);
            } else {
                log.info("Lecture {}: skip correction. chars={}, uncertain={}",
                        lectureId, tr.getCharsTotal(), tr.getUncertainCountTotal());
            }

            log.info("Lecture {}: PDF OCR done. chars={}, uncertain={}, ms={}",
                    lectureId, normalized.length(), tr.getUncertainCountTotal(),
                    (System.currentTimeMillis() - started));

            return new ProcessResult(normalized, LectureStatus.PROCESSED);

        } catch (Exception e) {
            log.error("Lecture {}: PDF OCR fallback failed", lectureId, e);
            return new ProcessResult("", LectureStatus.FAILED);
        }
    }

    private ProcessResult processImages(Long lectureId, List<LectureFile> savedFiles) {
        long started = System.currentTimeMillis();

        Path lectureDir = Path.of(lectureUploadDir, String.valueOf(lectureId));
        Path preDir = lectureDir.resolve("preprocessed");

        try {
            Files.createDirectories(preDir);

            List<Path> originalPaths = savedFiles.stream()
                    .map(f -> lectureDir.resolve(f.getStoredName()))
                    .toList();

            List<Path> inputs = new ArrayList<>(originalPaths.size());
            int preCount = 0;

            for (int i = 0; i < originalPaths.size(); i++) {
                Path original = originalPaths.get(i);
                Path processed = imagePreprocessorService.preprocess(original, preDir, i);
                inputs.add(processed);
                if (processed.startsWith(preDir)) preCount++;
            }

            log.info("Lecture {}: preprocess done. pages={}, preprocessedUsed={}",
                    lectureId, inputs.size(), preCount);

            // 1) OCR — распознаём текст с каждой страницы
            VisionTranscriptionResponse tr = visionTranscriptionService.transcribeLectureImages(inputs);

            // 2) Vision-коррекция ПОСТРАНИЧНО — модель видит И картинку И текст
            log.info("Lecture {}: vision correction start. pages={}, chars={}, uncertain={}",
                    lectureId,
                    tr.getPages() == null ? 0 : tr.getPages().size(),
                    tr.getCharsTotal(),
                    tr.getUncertainCountTotal());

            List<String> correctedPages = new ArrayList<>();
            if (tr.getPages() != null) {
                List<VisionTranscriptionResponse.Page> sorted = tr.getPages().stream()
                        .sorted(Comparator.comparingInt(VisionTranscriptionResponse.Page::getIndex))
                        .toList();

                for (int i = 0; i < sorted.size(); i++) {
                    VisionTranscriptionResponse.Page page = sorted.get(i);
                    String pageText = page.getText() == null ? "" : page.getText();
                    String normalized = normalizeRecognizedText(pageText);

                    if (!normalized.isBlank() && i < inputs.size()) {
                        try {
                            // Vision-коррекция: модель видит фото + OCR-текст
                            String corrected = textCorrectionService.correctWithImage(normalized, inputs.get(i));
                            correctedPages.add(corrected == null || corrected.isBlank() ? normalized : corrected);
                        } catch (Exception e) {
                            log.warn("Lecture {}: page {} vision correction failed, using OCR text",
                                    lectureId, page.getIndex(), e);
                            correctedPages.add(normalized);
                        }
                    } else {
                        correctedPages.add(normalized);
                    }
                }
            }

            String finalText = String.join(PAGE_BREAK, correctedPages);
            log.info("Lecture {}: vision correction done.", lectureId);

            log.info("Lecture {}: images OCR done. chars={}, uncertain={}, ms={}",
                    lectureId, finalText.length(), tr.getUncertainCountTotal(),
                    (System.currentTimeMillis() - started));

            return new ProcessResult(finalText, LectureStatus.PROCESSED);

        } catch (Exception e) {
            log.error("Images processing failed for lecture {}", lectureId, e);
            return new ProcessResult("", LectureStatus.FAILED);
        }
    }

    private List<Path> renderPdfToImages(File pdfFile, Path outputDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);

            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 170, ImageType.RGB);
                Path p = outputDir.resolve(String.format("pdf_page_%02d.jpg", i + 1));
                ImageIO.write(img, "jpg", p.toFile());
                out.add(p);
            }
        }
        return out;
    }

    private String mergePages(VisionTranscriptionResponse tr) {
        if (tr == null || tr.getPages() == null) return "";
        return tr.getPages().stream()
                .sorted(Comparator.comparingInt(VisionTranscriptionResponse.Page::getIndex))
                .map(p -> p.getText() == null ? "" : p.getText())
                .collect(Collectors.joining(PAGE_BREAK));
    }

    private boolean shouldRunCorrection(VisionTranscriptionResponse tr, String normalized) {
        if (normalized == null) normalized = "";

        int chars = Math.max(1, normalized.length());
        int uncertain = tr == null ? 0 : tr.getUncertainCountTotal();
        int charsTotal = tr == null ? normalized.length() : tr.getCharsTotal();

        double uncertainPer1k = uncertain * 1000.0 / chars;

        int pages = tr.getPages() == null ? 1 : Math.max(1, tr.getPages().size());
        int minChars = pages * 300;

        if (charsTotal < minChars && uncertain > 0) return true;
        if (uncertain > CORRECTION_UNCERTAIN_TOTAL) return true;
        if (uncertainPer1k > CORRECTION_UNCERTAIN_PER_1K) return true;

        long weird = normalized.chars()
                .filter(ch -> ch != '\n' && ch != '\r' && ch != '\t')
                .filter(ch -> !(Character.isLetterOrDigit(ch) || Character.isWhitespace(ch)
                        || ".,;:!?()[]{}<>+-=*/%№#'\"\\|_^~`".indexOf(ch) >= 0))
                .count();

        return weird > Math.max(20, chars * 0.01);
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

    @Override
    @Transactional
    public SummaryResponse generateLectureSummary(Long lectureId, String userEmail) {
        Lecture lecture = getLectureOwned(lectureId, userEmail);

        if (lecture.getStatus() != LectureStatus.PROCESSED) {
            return new SummaryResponse(lectureId, null, "not_processed");
        }

        // Проверяем кэш — если summary уже есть, возвращаем
        Optional<LectureSummary> existing = lectureSummaryRepository.findByLectureId(lectureId);
        if (existing.isPresent()) {
            return new SummaryResponse(lectureId, existing.get().getContent(), "ready");
        }

        // Получаем текст лекции
        LectureText lectureText = lectureTextRepository.findByLectureId(lectureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture text not found"));

        // Генерируем summary
        String summaryText = summaryGenerationService.generateSummary(lectureText.getContent());

        // Сохраняем в кэш
        LectureSummary summary = new LectureSummary();
        summary.setLecture(lecture);
        summary.setContent(summaryText);
        lectureSummaryRepository.save(summary);

        log.info("Lecture {}: summary generated and cached. chars={}", lectureId, summaryText.length());

        return new SummaryResponse(lectureId, summaryText, "ready");
    }

    @Override
    @Transactional(readOnly = true)
    public QuizLockedResponse getLectureQuiz(Long lectureId, String userEmail) {
        getLectureOwned(lectureId, userEmail);
        return new QuizLockedResponse(false, "Тест заблокирован. Сначала нужно изучить все карточки (прогресс ещё не реализован).");
    }

    @Override
    @Transactional(readOnly = true)
    public LectureDetailResponse getLectureDetail(Long lectureId, String userEmail) {

        Lecture lecture = getLectureOwned(lectureId, userEmail);

        // Текст
        String content = "";
        Optional<LectureText> txtOpt = lectureTextRepository.findByLectureId(lectureId);
        if (txtOpt.isPresent()) {
            content = txtOpt.get().getContent() == null ? "" : txtOpt.get().getContent();
        }

        // Файлы (фото страниц)
        List<LectureFileResponse> files = lectureFileRepository.findByLectureId(lectureId).stream()
                .sorted(Comparator.comparing(LectureFile::getOriginalName))
                .map(f -> {
                    int idx = lectureFileRepository.findByLectureId(lectureId).indexOf(f);
                    String url = "/uploads/lectures/" + lectureId + "/" + f.getStoredName();
                    return new LectureFileResponse(
                            f.getId(),
                            f.getOriginalName(),
                            url,
                            f.getContentType(),
                            idx + 1
                    );
                })
                .toList();

        return new LectureDetailResponse(
                lecture.getId(),
                lecture.getProject().getId(),
                lecture.getTitle(),
                lecture.getStatus().name(),
                content,
                files,
                lecture.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public LectureTextResponse updateLectureText(Long lectureId, String content, String userEmail) {

        getLectureOwned(lectureId, userEmail);

        LectureText txt = lectureTextRepository.findByLectureId(lectureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture text not found"));

        String newContent = content == null ? "" : content;
        txt.setContent(newContent);
        lectureTextRepository.save(txt);

        log.info("Lecture {}: text updated by user. chars={}", lectureId, newContent.length());

        return new LectureTextResponse(lectureId, newContent.length(), newContent);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LectureFileResponse> getLectureFiles(Long lectureId, String userEmail) {

        getLectureOwned(lectureId, userEmail);

        List<LectureFile> files = lectureFileRepository.findByLectureId(lectureId);
        files.sort(Comparator.comparing(LectureFile::getOriginalName));

        List<LectureFileResponse> result = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            LectureFile f = files.get(i);
            String url = "/uploads/lectures/" + lectureId + "/" + f.getStoredName();
            result.add(new LectureFileResponse(
                    f.getId(),
                    f.getOriginalName(),
                    url,
                    f.getContentType(),
                    i + 1
            ));
        }
        return result;
    }

    @Override
    @Transactional
    public void deleteLecture(Long lectureId, String userEmail) {

        Lecture lecture = getLectureOwned(lectureId, userEmail);

        // Удаляем файлы с диска
        Path lectureDir = Path.of(lectureUploadDir, String.valueOf(lectureId));
        try {
            if (Files.exists(lectureDir)) {
                Files.walk(lectureDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) { log.warn("Failed to delete file: {}", p); }
                        });
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup lecture dir: {}", lectureDir, e);
        }

        // Удаляем из БД (cascade удалит LectureText и LectureFile)
        lectureRepository.delete(lecture);

        log.info("Lecture {} deleted by {}", lectureId, userEmail);
    }

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