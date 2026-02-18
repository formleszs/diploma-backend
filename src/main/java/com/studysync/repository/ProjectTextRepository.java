package com.studysync.repository;


import com.studysync.entity.ProjectText;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectTextRepository extends JpaRepository<ProjectText, Long> {

    Optional<ProjectText> findByProjectId(Long projectId);
}