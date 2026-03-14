package com.studysync.repository;

import com.studysync.entity.Flashcard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlashcardRepository extends JpaRepository<Flashcard, Long> {

    List<Flashcard> findByLectureIdOrderBySortOrderAsc(Long lectureId);

    long countByLectureId(Long lectureId);

    long countByLectureIdAndStudiedTrue(Long lectureId);

    boolean existsByLectureId(Long lectureId);
}