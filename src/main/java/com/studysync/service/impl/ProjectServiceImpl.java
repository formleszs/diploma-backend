package com.studysync.service.impl;

import com.studysync.entity.Project;
import com.studysync.entity.ProjectFile;
import com.studysync.entity.ProjectText;
import com.studysync.entity.dto.response.CreateProjectResponse;
import com.studysync.entity.User;
import com.studysync.repository.ProjectFileRepository;
import com.studysync.repository.ProjectRepository;
import com.studysync.repository.ProjectTextRepository;
import com.studysync.repository.UserRepository;
import com.studysync.service.AiService;
import com.studysync.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final UserRepository userRepository;
    private final ProjectTextRepository projectTextRepository;
    private final PdfTextExtractionServiceImpl pdfTextExtractionService;
    private final AiService aiService;
    private final TextCompressionServiceImpl textCompressionService;
    private final TextChunkingServiceImpl textChunkingService;

    @Value("${app.upload.project-dir:./uploads/projects}")
    private String projectUploadDir;

    private static final long MAX_PDF_SIZE = 20 * 1024 * 1024;
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;

    @Override
    public CreateProjectResponse createProject(
            String projectName,
            MultipartFile[] files,
            String userEmail
    ) {

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Files are required");
        }

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        validateFiles(files);

        Project project = new Project();
        project.setName(projectName);
        project.setOwner(user);
        project.setCreatedAt(LocalDateTime.now());

        log.info("User {} is creating project with name: {}", user.getEmail(), projectName);
        project = projectRepository.save(project);

        List<ProjectFile> savedFiles = saveFiles(files, project);
        if (savedFiles.size() == 1
                && "application/pdf".equals(savedFiles.get(0).getContentType())) {

            Path pdfPath = Path.of(
                    projectUploadDir,
                    String.valueOf(project.getId()),
                    savedFiles.get(0).getStoredName()
            );

            String extractedText = pdfTextExtractionService.extractText(pdfPath.toFile());

            ProjectText projectText = new ProjectText();
            projectText.setProject(project);
            projectText.setContent(extractedText);
            projectText.setCreatedAt(LocalDateTime.now());

            projectTextRepository.save(projectText);
        }


        log.info("Project successfully created for user {}", user.getEmail());

        return new CreateProjectResponse(
                project.getId(),
                project.getName(),
                project.getCreatedAt()
        );
    }

    private void validateFiles(MultipartFile[] files) {

        boolean hasPdf = false;
        int imageCount = 0;

        for (MultipartFile file : files) {

            String contentType = file.getContentType();

            if ("application/pdf".equals(contentType)) {
                hasPdf = true;

                if (file.getSize() > MAX_PDF_SIZE) {
                    throw new IllegalArgumentException("PDF max size is 20MB");
                }

            } else if (contentType != null && contentType.startsWith("image/")) {
                imageCount++;

                if (file.getSize() > MAX_IMAGE_SIZE) {
                    throw new IllegalArgumentException("Image max size is 5MB");
                }

            } else {
                throw new IllegalArgumentException("Unsupported file type");
            }
        }

        if (hasPdf && files.length > 1) {
            throw new IllegalArgumentException("Only one PDF allowed");
        }

        if (!hasPdf && imageCount > 10) {
            throw new IllegalArgumentException("Max 10 images allowed");
        }
    }

    private List<ProjectFile> saveFiles(MultipartFile[] files, Project project) {

        List<ProjectFile> savedFiles = new ArrayList<>();

        try {
            Path projectDir = Path.of(projectUploadDir, String.valueOf(project.getId()));
            Files.createDirectories(projectDir);

            for (MultipartFile file : files) {

                String extension = getExtension(file.getOriginalFilename());
                String storedName = UUID.randomUUID() + extension;

                Path filePath = projectDir.resolve(storedName);

                Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

                ProjectFile projectFile = new ProjectFile();
                projectFile.setProject(project);
                projectFile.setOriginalName(file.getOriginalFilename());
                projectFile.setStoredName(storedName);
                projectFile.setContentType(file.getContentType());
                projectFile.setSize(file.getSize());

                projectFileRepository.save(projectFile);
                savedFiles.add(projectFile);
            }

            return savedFiles;

        } catch (IOException e) {
            throw new RuntimeException("Failed to store files", e);
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    @Override
    public CreateProjectResponse getProjectById(Long id) {

        log.info("Fetching project with id {}", id);

        Project project = projectRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Project {} not found", id);
                    return new RuntimeException("Project not found");
                });

        return new CreateProjectResponse(
                project.getId(),
                project.getName(),
                project.getCreatedAt()
        );
    }

    @Override
    public List<CreateProjectResponse> getProjectsForCurrentUser() {

        Authentication auth = SecurityContextHolder
                .getContext()
                .getAuthentication();

        String email = auth.getName();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Project> projects = projectRepository.findByOwner(user);

        return projects.stream()
                .map(this::mapToResponse)
                .toList();
    }

    private CreateProjectResponse mapToResponse(Project project) {
        return new CreateProjectResponse(
                project.getId(),
                project.getName(),
                project.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    @Override
    public String getProjectText(Long projectId) {
        String currentUserEmail = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getOwner().getEmail().equals(currentUserEmail)) {
            throw new RuntimeException("Forbidden");
        }

        ProjectText projectText = projectTextRepository
                .findByProjectId(projectId)
                .orElseThrow(() -> new RuntimeException("Text not found"));

        return projectText.getContent();
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId) {

        String currentUserEmail = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getOwner().getEmail().equals(currentUserEmail)) {
            throw new RuntimeException("Forbidden");
        }

        // Удаляем папку с файлами
        Path projectDir = Path.of(projectUploadDir, String.valueOf(projectId));

        try {
            if (Files.exists(projectDir)) {
                Files.walk(projectDir)
                        .sorted((a, b) -> b.compareTo(a)) // сначала файлы, потом папки
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.error("Failed to delete file: {}", path, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Error deleting project directory", e);
        }

        projectRepository.delete(project);

        log.info("Project {} deleted by {}", projectId, currentUserEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public String generateTopics(Long projectId) {

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        String originalText = project.getProjectText().getContent();

        if (originalText == null || originalText.isBlank()) {
            throw new RuntimeException("Project text is empty");
        }

        log.info("Original length: {}", originalText.length());

        // 1. Сжатие
        String compressedText = textCompressionService.compress(originalText);

        log.info("Compressed length: {}", compressedText.length());

        // 2. Разбиение на чанки
        List<String> chunks = textChunkingService.splitIntoChunks(compressedText, 8000);

        log.info("Chunks count: {}", chunks.size());

        List<String> allTopics = new ArrayList<>();

        // 3. Генерация тем по каждому чанку
        for (String chunk : chunks) {

            String prompt = """
                Ты анализируешь часть учебной лекции.
                    
                Выдели от 5 до 10 основных тем.
                Не меньше 5.
                Не больше 10.
                    
                Темы должны быть конкретными, а не общими.
                    
                Верни строго JSON:
                {
                  "topics": [
                    {"title": "Название темы"}
                  ]
                }
                    
                Без пояснений.
                
                Текст:
                """ + chunk;

            String response = aiService.generate(prompt);
            allTopics.add(response);
        }

        // 4. Объединение результатов
        String combined = String.join("\n", allTopics);

        String finalPrompt = """
            Ниже список тем, полученных из разных частей лекции.
            
            Объедини похожие темы.
            Удали дубликаты.
            Верни итоговый список в формате JSON:
            
            {
              "topics": [
                {"title": "Название темы"}
              ]
            }
            
            Список:
            """ + combined;

        return aiService.generate(finalPrompt);
    }


}
