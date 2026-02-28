package com.studysync.service;

import com.studysync.entity.dto.response.VisionTranscriptionResponse;

import java.nio.file.Path;
import java.util.List;

public interface VisionTranscriptionService {
    VisionTranscriptionResponse transcribeLectureImages(List<Path> imagePaths);
}