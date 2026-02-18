package com.studysync.repository;

import com.studysync.entity.Project;
import com.studysync.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwner(User owner);

    long countByOwner(User owner);
}
