package com.studysync.repository;

import com.studysync.entity.LectureSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LectureSummaryRepository extends JpaRepository<LectureSummary, Long> {

    Optional<LectureSummary> findByLectureId(Long lectureId);

    boolean existsByLectureId(Long lectureId);
}