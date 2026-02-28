package com.studysync.repository;

import com.studysync.entity.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

    List<Lecture> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}