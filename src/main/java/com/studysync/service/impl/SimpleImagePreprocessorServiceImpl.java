package com.studysync.service.impl;

import com.studysync.service.ImagePreprocessorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Стабильный препроцессор для Vision OCR. Чистый Java (без OpenCV).
 *
 * Пайплайн:
 *   1) downscale до рабочего размера (экономим CPU на анализе)
 *   2) адаптивный ink-bbox → crop ближе к тексту (убирает стол/пальцы/пустоту)
 *   3) финальный downscale (меньше пикселей = дешевле OCR)
 *   4) лёгкая коррекция контраста (рукописный текст на бумаге)
 *   5) sanity-check: если результат почти белый → fallback на оригинал
 *
 * НЕ делает: perspective warp, бинаризацию, агрессивный threshold.
 * Цель: стабильно улучшить, никогда не ухудшить.
 */
@Slf4j
@Service
public class SimpleImagePreprocessorServiceImpl implements ImagePreprocessorService {

    // --- Размеры ---
    private static final int MAX_SIDE_DEFAULT = 1600;
    private static final int MAX_SIDE_FAR_SHOT = 2000;
    private static final int MAX_SIDE_WORK_CAP = 2200;

    // --- JPEG quality ---
    private static final float JPEG_QUALITY_DEFAULT = 0.85f;
    private static final float JPEG_QUALITY_FAR_SHOT = 0.90f;

    // --- Коррекция контраста ---
    // Чуть подняли с 1.04 — рукописный текст часто бледный на фото
    private static final float CONTRAST = 1.08f;
    private static final float BRIGHTNESS = 2f;

    // --- Ink detection ---
    private static final double MARGIN_RATIO = 0.02;
    private static final double Q_LOW = 0.005;
    private static final double Q_HIGH = 0.995;
    private static final double THR_MIN = 70.0;
    private static final double THR_MAX = 160.0;
    private static final int MIN_INK_POINTS = 80;
    private static final int SAMPLE_STEP_MIN = 2;

    // --- Sanity checks ---
    private static final double TOO_WHITE_MEAN_LUMA = 248.0;
    private static final double TOO_DARK_MEAN_LUMA = 40.0;

    // --- Far shot detection ---
    private static final double FAR_SHOT_INK_RATIO = 0.25;

    @Override
    public Path preprocess(Path inputImage, Path outputDir, int index) {
        try {
            Files.createDirectories(outputDir);

            BufferedImage src = ImageIO.read(inputImage.toFile());
            if (src == null) {
                log.warn("Preprocess: can't read image, fallback to original: {}", inputImage);
                return inputImage;
            }

            BufferedImage rgb = toRgb(src);

            // 1) Рабочая копия для анализа
            BufferedImage work = downscale(rgb, MAX_SIDE_WORK_CAP);

            // 2) Ink bbox
            BBoxResult bb = findInkBBox(work);

            // 3) Far shot detection
            boolean farShot = bb.valid && bb.inkAreaRatio < FAR_SHOT_INK_RATIO;
            int targetMaxSide = farShot ? MAX_SIDE_FAR_SHOT : MAX_SIDE_DEFAULT;
            float jpegQ = farShot ? JPEG_QUALITY_FAR_SHOT : JPEG_QUALITY_DEFAULT;

            // 4) Crop
            BufferedImage cropped = work;
            if (bb.valid) {
                int pad = clampInt(
                        (int) Math.round(Math.min(work.getWidth(), work.getHeight()) * 0.04),
                        30, 80
                );
                Rect padded = bb.rect.pad(pad, work.getWidth(), work.getHeight());
                cropped = work.getSubimage(padded.x1, padded.y1, padded.width(), padded.height());
            }

            // 5) Финальный downscale
            BufferedImage finalImg = downscale(cropped, targetMaxSide);

            // 6) Коррекция контраста
            finalImg = adjustContrastBrightness(finalImg, CONTRAST, BRIGHTNESS);

            // 7) Sanity checks
            double mean = meanLuminance(finalImg);
            if (mean >= TOO_WHITE_MEAN_LUMA) {
                log.warn("Preprocess: too white (meanLuma={:.1f}), fallback to original: {}", mean, inputImage);
                return inputImage;
            }
            if (mean <= TOO_DARK_MEAN_LUMA) {
                log.warn("Preprocess: too dark (meanLuma={:.1f}), fallback to original: {}", mean, inputImage);
                return inputImage;
            }

            Path out = outputDir.resolve(String.format("page_%02d.jpg", index + 1));
            writeJpeg(finalImg, out, jpegQ);

            log.debug("Preprocess ok: in={}, out={}, work={}x{}, cropped={}x{}, final={}x{}, farShot={}, inkRatio={:.3f}",
                    inputImage.getFileName(), out.getFileName(),
                    work.getWidth(), work.getHeight(),
                    cropped.getWidth(), cropped.getHeight(),
                    finalImg.getWidth(), finalImg.getHeight(),
                    farShot, bb.inkAreaRatio);

            return out;

        } catch (Exception e) {
            log.warn("Preprocess failed, fallback to original: {}", inputImage, e);
            return inputImage;
        }
    }

    // ========== Image utils ==========

    private static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static BufferedImage downscale(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxSide) return src;

        double scale = maxSide / (double) max;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static BufferedImage adjustContrastBrightness(BufferedImage src, float scale, float offset) {
        RescaleOp op = new RescaleOp(
                new float[]{scale, scale, scale},
                new float[]{offset, offset, offset},
                null
        );
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        op.filter(src, out);
        return out;
    }

    // ========== Luminance ==========

    private static double meanLuminance(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(SAMPLE_STEP_MIN, Math.min(w, h) / 400);

        long sum = 0;
        long cnt = 0;

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                sum += luma(img.getRGB(x, y));
                cnt++;
            }
        }
        return cnt == 0 ? 255.0 : sum / (double) cnt;
    }

    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    // ========== Ink BBox detection ==========

    /**
     * Находит bbox "чернил" адаптивно:
     * - берём выборку яркости по внутренней области (margin 5%)
     * - считаем p10 и p50
     * - thr = clamp(70..160, p10 + 0.35*(p50 - p10))
     * - собираем координаты точек luma < thr
     * - bbox по Q_LOW/Q_HIGH перцентилям координат (устойчиво к шуму)
     */
    private static BBoxResult findInkBBox(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(SAMPLE_STEP_MIN, Math.min(w, h) / 650);

        int mx = (int) Math.round(w * MARGIN_RATIO);
        int my = (int) Math.round(h * MARGIN_RATIO);

        int x0 = mx;
        int x1 = Math.max(mx + 1, w - mx);
        int y0 = my;
        int y1 = Math.max(my + 1, h - my);

        // 1) Собираем luminance sample
        double[] lum = new double[((x1 - x0) / step + 2) * ((y1 - y0) / step + 2)];
        int n = 0;

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                lum[n++] = luma(img.getRGB(x, y));
            }
        }
        if (n < 100) return new BBoxResult(false, new Rect(w, h, -1, -1), 0, 1.0);

        lum = Arrays.copyOf(lum, n);
        Arrays.sort(lum);

        double p10 = lum[(int) Math.floor(n * 0.10)];
        double p50 = lum[(int) Math.floor(n * 0.50)];

        double thr = p10 + 0.35 * (p50 - p10);
        thr = clampDouble(thr, THR_MIN, THR_MAX);

        // 2) Координаты "чернильных" точек
        int[] xs = new int[4096];
        int[] ys = new int[4096];
        int k = 0;

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                double l = luma(img.getRGB(x, y));
                if (l < thr) {
                    if (k == xs.length) {
                        xs = Arrays.copyOf(xs, xs.length * 2);
                        ys = Arrays.copyOf(ys, ys.length * 2);
                    }
                    xs[k] = x;
                    ys[k] = y;
                    k++;
                }
            }
        }

        if (k < MIN_INK_POINTS) {
            return new BBoxResult(false, new Rect(w, h, -1, -1), k, 1.0);
        }

        xs = Arrays.copyOf(xs, k);
        ys = Arrays.copyOf(ys, k);
        Arrays.sort(xs);
        Arrays.sort(ys);

        int ix1 = xs[(int) Math.floor(k * Q_LOW)];
        int ix2 = xs[(int) Math.floor(k * Q_HIGH)];
        int iy1 = ys[(int) Math.floor(k * Q_LOW)];
        int iy2 = ys[(int) Math.floor(k * Q_HIGH)];

        Rect r = new Rect(ix1, iy1, ix2, iy2);

        boolean valid = r.isValid();
        double areaRatio = valid ? (r.width() * r.height()) / (double) (w * h) : 1.0;

        return new BBoxResult(valid, r, k, areaRatio);
    }

    // ========== JPEG writing ==========

    private static void writeJpeg(BufferedImage img, Path out, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(img, "jpg", out.toFile());
            return;
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();

        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            float q = Math.max(0.0f, Math.min(1.0f, quality));
            param.setCompressionQuality(q);
        }

        try (OutputStream os = Files.newOutputStream(out);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ========== Utils ==========

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ========== Inner classes ==========

    private static class Rect {
        final int x1, y1, x2, y2;

        Rect(int x1, int y1, int x2, int y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        boolean isValid() {
            return x2 >= x1 && y2 >= y1;
        }

        int width() {
            return x2 - x1 + 1;
        }

        int height() {
            return y2 - y1 + 1;
        }

        Rect pad(int padding, int maxW, int maxH) {
            int nx1 = Math.max(0, x1 - padding);
            int ny1 = Math.max(0, y1 - padding);
            int nx2 = Math.min(maxW - 1, x2 + padding);
            int ny2 = Math.min(maxH - 1, y2 + padding);
            return new Rect(nx1, ny1, nx2, ny2);
        }
    }

    private static class BBoxResult {
        final boolean valid;
        final Rect rect;
        final int inkPoints;
        final double inkAreaRatio;

        BBoxResult(boolean valid, Rect rect, int inkPoints, double inkAreaRatio) {
            this.valid = valid;
            this.rect = rect;
            this.inkPoints = inkPoints;
            this.inkAreaRatio = inkAreaRatio;
        }
    }
}