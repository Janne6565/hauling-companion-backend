package com.janne6565.haulingcompanionapi.services.ocr;

import com.janne6565.haulingcompanionapi.configuration.TesseractProperties;
import com.janne6565.haulingcompanionapi.model.core.BoundingBoxDto;
import com.janne6565.haulingcompanionapi.model.core.MissionLegDto;
import com.janne6565.haulingcompanionapi.model.core.ParsedMissionDto;
import com.janne6565.haulingcompanionapi.model.core.RegionConfigDto;
import com.janne6565.haulingcompanionapi.services.scmdb.ScmdbService;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    // Matches "X in Y" — greedy left side so the last " in " is the split point
    private static final Pattern IN_LOCATION_PATTERN =
            Pattern.compile("(?i)^(.+)\\s+in\\s+(.+)$");

    private static final Pattern REWARD_PATTERN =
            Pattern.compile("(\\d{1,3}(?:[,.]\\d{3})+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLLECT_PATTERN =
            Pattern.compile(
                    "Collect\\s+(.+?)\\s+from\\s+(.+?)(?:\\r?\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELIVER_PATTERN =
            Pattern.compile(
                    "Deliver\\s+(\\d+)/(\\d+)\\s+SCU\\s+of\\s+(.+?)\\s*to\\s+(.+?)(?:\\r?\\n|$)",
                    Pattern.CASE_INSENSITIVE);

    private final TesseractProperties tesseractProperties;
    private final ScmdbService scmdbService;

    public ParsedMissionDto parse(MultipartFile file, RegionConfigDto regions) {
        try {
            BufferedImage full = ImageIO.read(file.getInputStream());

            String titleOcr = ocrRegion(full, regions.title(), 6);
            String rewardOcr = ocrRegion(full, regions.reward(), 7);
            String objectivesOcr = ocrRegion(full, regions.objectives(), 6);

            log.info("Title OCR: {}", titleOcr.trim());
            log.info("Reward OCR: {}", rewardOcr.trim());
            log.info("Objectives OCR length: {} chars", objectivesOcr.length());

            return extractMission(titleOcr, rewardOcr, objectivesOcr);
        } catch (Exception e) {
            log.error("OCR failed for {}: {}", file.getOriginalFilename(), e.getMessage());
            throw new RuntimeException("Failed to parse screenshot: " + e.getMessage(), e);
        }
    }

    private String ocrRegion(BufferedImage full, BoundingBoxDto box, int psm)
            throws TesseractException, Exception {
        int x = clamp((int) (box.x() * full.getWidth()), 0, full.getWidth() - 1);
        int y = clamp((int) (box.y() * full.getHeight()), 0, full.getHeight() - 1);
        int w = clamp((int) (box.w() * full.getWidth()), 1, full.getWidth() - x);
        int h = clamp((int) (box.h() * full.getHeight()), 1, full.getHeight() - y);

        BufferedImage region = full.getSubimage(x, y, w, h);
        BufferedImage processed = preprocess(region);

        File tmp = Files.createTempFile("region-", ".png").toFile();
        try {
            ImageIO.write(processed, "PNG", tmp);
            Tesseract t = new Tesseract();
            t.setDatapath(tesseractProperties.getDataPath());
            t.setLanguage(tesseractProperties.getLanguage());
            t.setPageSegMode(psm);
            return t.doOCR(tmp);
        } finally {
            tmp.delete();
        }
    }

    private BufferedImage preprocess(BufferedImage src) {
        // Grayscale
        BufferedImage gray =
                new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        // Upscale 2× — Tesseract accuracy improves significantly on larger text
        int w = gray.getWidth() * 2;
        int h = gray.getHeight() * 2;
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.drawImage(gray, 0, 0, w, h, null);
        g2.dispose();
        return scaled;
    }

    private ParsedMissionDto extractMission(String titleOcr, String rewardOcr, String objectivesOcr) {
        String title = scmdbService.findBestTitleMatch(titleOcr);
        if (title == null) {
            title = titleOcr.trim().replaceAll("\\s+", " ");
            log.warn("No SCMDB title match — using raw OCR: '{}'", title);
        }

        Integer reward = extractReward(rewardOcr);
        List<MissionLegDto> pickups = extractPickups(objectivesOcr);
        List<MissionLegDto> deliveries = extractDeliveries(objectivesOcr);

        // Assign pickup SCU from matching deliveries (unambiguous when one pickup per cargo)
        Map<String, Long> pickupsPerCargo =
                pickups.stream()
                        .collect(
                                Collectors.groupingBy(
                                        p -> p.cargoType() != null ? p.cargoType() : "",
                                        Collectors.counting()));
        final List<MissionLegDto> finalDeliveries = deliveries;
        pickups =
                pickups.stream()
                        .map(
                                pickup -> {
                                    String cargo = pickup.cargoType() != null ? pickup.cargoType() : "";
                                    if (pickupsPerCargo.getOrDefault(cargo, 0L) != 1) return pickup;
                                    int totalScu =
                                            finalDeliveries.stream()
                                                    .filter(d -> Objects.equals(d.cargoType(), pickup.cargoType()))
                                                    .mapToInt(d -> d.scu() != null ? d.scu() : 0)
                                                    .sum();
                                    return totalScu > 0
                                            ? pickup.toBuilder().scu(totalScu).build()
                                            : pickup;
                                })
                        .collect(Collectors.toList());

        String cargoType = extractFirstCargo(objectivesOcr);
        int orderCount = !pickups.isEmpty() ? pickups.size() : deliveries.size();
        Integer xp = scmdbService.lookupXp(title, reward, cargoType, orderCount);

        log.info("Parsed: title='{}' reward={} xp={} pickups={} deliveries={}",
                title, reward, xp, pickups.size(), deliveries.size());

        return ParsedMissionDto.builder()
                .title(title)
                .rewardUec(reward)
                .xp(xp)
                .cargoType(cargoType)
                .orderCount(orderCount)
                .pickups(pickups)
                .deliveries(deliveries)
                .rawOcrText(titleOcr + "\n---\n" + rewardOcr + "\n---\n" + objectivesOcr)
                .build();
    }

    private Integer extractReward(String text) {
        Matcher m = REWARD_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1).replace(",", "").replace(".", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private List<MissionLegDto> extractPickups(String text) {
        List<MissionLegDto> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = COLLECT_PATTERN.matcher(text);
        while (m.find()) {
            String cargo = m.group(1).trim();
            String loc = cleanLocation(m.group(2));
            if (seen.add(loc + "|" + cargo)) {
                result.add(MissionLegDto.builder().location(loc).cargoType(cargo).build());
            }
        }
        return result;
    }

    private List<MissionLegDto> extractDeliveries(String text) {
        List<MissionLegDto> result = new ArrayList<>();
        Matcher m = DELIVER_PATTERN.matcher(text);
        while (m.find()) {
            int scu = parseInt(m.group(2));
            String cargo = m.group(3).trim();
            result.add(MissionLegDto.builder()
                    .scu(scu)
                    .location(cleanLocation(m.group(4)))
                    .cargoType(cargo)
                    .build());
        }
        return result;
    }

    private String extractFirstCargo(String text) {
        Matcher deliver = DELIVER_PATTERN.matcher(text);
        if (deliver.find()) return deliver.group(3).trim();
        Matcher collect = COLLECT_PATTERN.matcher(text);
        if (collect.find()) return collect.group(1).trim();
        return null;
    }

    private String cleanLocation(String raw) {
        String s = raw.trim().replaceAll("[\\[|.,]+$", "").trim();

        // "X in Y" → use Y (the containing city/area is the searchable location,
        // e.g. "Teasa Spaceport in Lorville" → "Lorville")
        Matcher inMatcher = IN_LOCATION_PATTERN.matcher(s);
        if (inMatcher.matches()) {
            return inMatcher.group(2).trim();
        }

        // "X on Y" or "X at Y" → X is the location name, strip the body suffix
        return s.replaceAll("(?i)\\s+on\\s+.*$", "")
                .replaceAll("(?i)\\s+on\\s*$", "")
                .replaceAll("(?i)(?<=\\S)on$", "")
                .replaceAll("(?i)\\s+at\\s+\\S.*$", "")
                .replaceAll("(?i)\\s+at\\s*$", "")
                .trim();
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
