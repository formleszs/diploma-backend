package com.studysync.repository;

import com.studysync.entity.LectureFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LectureFileRepository extends JpaRepository<LectureFile, Long> {

    List<LectureFile> findByLectureId(Long lectureId);

    long countByLectureId(Long lectureId);

    boolean existsByLectureId(Long lectureId);
}