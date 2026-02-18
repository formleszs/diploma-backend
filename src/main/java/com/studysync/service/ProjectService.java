package com.studysync.service;

import com.studysync.entity.dto.response.CreateProjectResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ProjectService {

    CreateProjectResponse createProject(
            String projectName,
            MultipartFile[] files,
            String userEmail
    );

    CreateProjectResponse getProjectById(Long id);

    List<CreateProjectResponse> getProjectsForCurrentUser();

    String getProjectText(Long projectId);

    void deleteProject(Long projectId);

    String generateTopics(Long projectId);
}