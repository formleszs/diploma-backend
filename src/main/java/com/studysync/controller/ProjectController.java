package com.studysync.controller;

import com.studysync.entity.User;
import com.studysync.entity.dto.response.CreateProjectResponse;
import com.studysync.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping(consumes = "multipart/form-data")
    public CreateProjectResponse createProject(
            @RequestParam("name") String name,
            @RequestParam("files") MultipartFile[] files,
            Authentication authentication
    ) {

        return projectService.createProject(
                name,
                files,
                authentication.getName()
        );
    }

    @GetMapping("/{id}")
    public CreateProjectResponse getProject(@PathVariable("id") Long id) {
        return projectService.getProjectById(id);
    }

    @GetMapping
    public List<CreateProjectResponse> getMyProjects() {
        return projectService.getProjectsForCurrentUser();
    }

    @GetMapping("/{id}/text")
    public String getProjectText(@PathVariable("id") Long id) {
        return projectService.getProjectText(id);
    }

    @DeleteMapping("/{id}")
    public void deleteProject(@PathVariable("id") Long id) {
        projectService.deleteProject(id);
    }

    @PostMapping("/{id}/topics/generate")
    public String generateTopics(@PathVariable("id") Long id) {
        return projectService.generateTopics(id);
    }
}
