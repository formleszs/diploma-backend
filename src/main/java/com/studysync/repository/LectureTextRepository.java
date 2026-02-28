package com.studysync.repository;

import com.studysync.entity.LectureText;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LectureTextRepository extends JpaRepository<LectureText, Long> {

    Optional<LectureText> findByLectureId(Long lectureId);
}