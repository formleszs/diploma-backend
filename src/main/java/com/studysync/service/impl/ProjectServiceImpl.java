package com.studysync.service.impl;

import com.studysync.entity.Project;
import com.studysync.entity.User;
import com.studysync.entity.dto.response.CreateProjectResponse;
import com.studysync.repository.ProjectRepository;
import com.studysync.repository.UserRepository;
import com.studysync.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    // пока оставим, чтобы delete мог чистить мусор. Позже лекции сделают свою структуру.
    @Value("${app.upload.project-dir:./uploads/projects}")
    private String projectUploadDir;

    @Override
    @Transactional
    public CreateProjectResponse createProject(String name, String userEmail) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = new Project();
        project.setName(name);
        project.setOwner(user);
        project.setCreatedAt(LocalDateTime.now());

        Project saved = projectRepository.save(project);

        log.info("Project {} created by {}", saved.getId(), userEmail);

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreateProjectResponse> getMyProjects(String userEmail) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return projectRepository.findByOwner(user).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CreateProjectResponse getProjectById(Long projectId, String userEmail) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        // owner LAZY — но метод transactional readOnly, так что ок
        if (!project.getOwner().getId().equals(user.getId())) {
            throw new RuntimeException("Forbidden");
        }

        return toResponse(project);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId, String userEmail) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getOwner().getId().equals(user.getId())) {
            throw new RuntimeException("Forbidden");
        }

        // Чистим папку проекта (на будущее: там будут лекции)
        Path projectDir = Path.of(projectUploadDir, String.valueOf(projectId));
        deleteDirectoryQuietly(projectDir);

        projectRepository.delete(project);

        log.info("Project {} deleted by {}", projectId, userEmail);
    }

    private void deleteDirectoryQuietly(Path dir) {
        try {
            if (!Files.exists(dir)) return;

            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a)) // сначала файлы, потом папки
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to delete project directory {}", dir, e);
        }
    }

    private CreateProjectResponse toResponse(Project project) {
        return new CreateProjectResponse(
                project.getId(),
                project.getName(),
                project.getCreatedAt()
        );
    }
}