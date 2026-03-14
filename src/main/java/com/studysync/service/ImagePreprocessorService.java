package com.studysync.service;

import java.nio.file.Path;

public interface ImagePreprocessorService {

    /**
     * Preprocesses an image for Vision OCR.
     *
     * @param inputImage original image path
     * @param outputDir directory to store preprocessed images
     * @param index zero-based page index (used for deterministic file naming)
     * @return path to the preprocessed image, or inputImage if preprocessing was skipped / deemed плохим
     */
    Path preprocess(Path inputImage, Path outputDir, int index);
}