package com.studysync.controller;

import com.studysync.entity.dto.request.CreateProjectRequest;
import com.studysync.entity.dto.response.CreateProjectResponse;
import com.studysync.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    public CreateProjectResponse createProject(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication
    ) {
        return projectService.createProject(request.getName(), authentication.getName());
    }

    @GetMapping
    public List<CreateProjectResponse> getMyProjects(Authentication authentication) {
        return projectService.getMyProjects(authentication.getName());
    }

    @GetMapping("/{id}")
    public CreateProjectResponse getProject(
            @PathVariable("id") Long id,
            Authentication authentication
    ) {
        return projectService.getProjectById(id, authentication.getName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable("id") Long id,
            Authentication authentication
    ) {
        projectService.deleteProject(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}