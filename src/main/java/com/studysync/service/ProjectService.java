package com.studysync.service;

import com.studysync.entity.dto.response.CreateProjectResponse;

import java.util.List;

public interface ProjectService {

    CreateProjectResponse createProject(String name, String userEmail);

    List<CreateProjectResponse> getMyProjects(String userEmail);

    CreateProjectResponse getProjectById(Long projectId, String userEmail);

    void deleteProject(Long projectId, String userEmail);
}