package com.janne6565.haulingcompanionapi.services.scmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janne6565.haulingcompanionapi.configuration.HaulerProperties;
import com.janne6565.haulingcompanionapi.model.core.XpIndexEntryDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScmdbService {

    private final HaulerProperties haulerProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // primary:   "title|rewardUEC"              -> xp  (exact reward match)
    // secondary: "title|cargoType|orderCount"   -> xp  (cargo+count match, first wins)
    // fallback:  "title|orderCount"             -> xp  (title+count only, first wins)
    private final Map<String, Integer> primaryIndex = new HashMap<>();
    private final Map<String, Integer> secondaryIndex = new HashMap<>();
    private final Map<String, Integer> fallbackIndex = new HashMap<>();
    private final List<XpIndexEntryDto> allEntries = new ArrayList<>();
    private final Set<String> knownTitles = new LinkedHashSet<>();

    @EventListener(ApplicationStartedEvent.class)
    public void loadScmdb() {
        log.info("Loading SCMDB data from {}", haulerProperties.getScmdbUrl());
        try {
            String json =
                    webClient
                            .get()
                            .uri(haulerProperties.getScmdbUrl())
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            JsonNode root = objectMapper.readTree(json);
            JsonNode missions = root.isArray() ? root : root.path("contracts");
            JsonNode resourcePools = root.path("resourcePools");
            JsonNode factionRewardsPools = root.path("factionRewardsPools");

            Set<String> missionTypes = new TreeSet<>();
            missions.forEach(m -> missionTypes.add(m.path("missionType").asText("(missing)")));
            log.info("SCMDB mission types found ({}): {}", missionTypes.size(), missionTypes);

            int titlesAdded = 0;
            int xpIndexed = 0;
            for (JsonNode m : missions) {
                String missionType = m.path("missionType").asText("");
                if (!missionType.startsWith("Hauling")) continue;

                String title = m.path("title").asText(null);
                if (title == null) continue;

                knownTitles.add(title);
                titlesAdded++;

                int xp = 0;
                JsonNode factionRewardsIndexNode = m.path("factionRewardsIndex");
                if (!factionRewardsIndexNode.isNull() && !factionRewardsIndexNode.isMissingNode()) {
                    JsonNode pool = factionRewardsPools.get(factionRewardsIndexNode.asInt());
                    if (pool != null) {
                        for (JsonNode reward : pool) {
                            int amount = reward.path("amount").asInt(0);
                            if (amount > 0) xp += amount;
                        }
                    }
                }
                if (xp == 0) continue;

                Integer rewardUec = readRewardUec(m);

                JsonNode orders = m.path("haulingOrders");
                int orderCount = orders.size();

                List<String> resourceNames = new ArrayList<>();
                for (JsonNode order : orders) {
                    String resourceId = order.path("resource").asText(null);
                    if (resourceId != null) {
                        String name = resourcePools.path(resourceId).path("name").asText(null);
                        if (name != null && !resourceNames.contains(name)) resourceNames.add(name);
                    }
                }

                String cargoType = resourceNames.isEmpty() ? "Unknown" : resourceNames.get(0);
                if (rewardUec != null) primaryIndex.put(title + "|" + rewardUec, xp);
                secondaryIndex.putIfAbsent(title + "|" + normalize(cargoType) + "|" + orderCount, xp);
                fallbackIndex.putIfAbsent(title + "|" + orderCount, xp);
                allEntries.add(XpIndexEntryDto.builder()
                        .title(title)
                        .cargoType(cargoType)
                        .orderCount(orderCount)
                        .rewardUec(rewardUec)
                        .xp(xp)
                        .build());
                xpIndexed++;
            }
            log.info("SCMDB loaded: {} titles for matching, {} with XP", titlesAdded, xpIndexed);
        } catch (Exception e) {
            log.warn("Failed to load SCMDB data: {}", e.getMessage());
        }
    }

    public String findBestTitleMatch(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) return null;
        if (knownTitles.isEmpty()) {
            log.warn("findBestTitleMatch called but knownTitles is empty — SCMDB not loaded?");
            return null;
        }

        Set<String> ocrWords = tokenize(ocrText);
        String bestTitle = null;
        double bestScore = 0.0;

        for (String title : knownTitles) {
            Set<String> titleWords = tokenize(title);
            if (titleWords.isEmpty()) continue;
            long matches = titleWords.stream().filter(ocrWords::contains).count();
            double score = (double) matches / titleWords.size();
            if (score > bestScore) {
                bestScore = score;
                bestTitle = title;
            }
        }

        log.info("Title match: '{}' (score={:.2f}, threshold=0.60)", bestTitle, bestScore);
        return bestScore >= 0.6 ? bestTitle : null;
    }

    public Integer lookupXp(String title, Integer rewardUec, String cargoType, int orderCount) {
        if (title == null) return null;

        // Exact reward match
        if (rewardUec != null) {
            Integer xp = primaryIndex.get(title + "|" + rewardUec);
            if (xp != null) {
                log.info("XP lookup: exact match for '{}' reward={} → {}", title, rewardUec, xp);
                return xp;
            }
        }

        // Closest-reward match within 25% — much more reliable than putIfAbsent fallbacks
        if (rewardUec != null) {
            final int reward = rewardUec;
            return allEntries.stream()
                    .filter(e -> title.equals(e.title()) && e.rewardUec() != null)
                    .filter(e -> Math.abs(e.rewardUec() - reward) <= reward * 0.25)
                    .min(Comparator.comparingInt(e -> Math.abs(e.rewardUec() - reward)))
                    .map(e -> {
                        log.info(
                                "XP lookup: closest match for '{}' reward={} → scmdb reward={} xp={}",
                                title,
                                reward,
                                e.rewardUec(),
                                e.xp());
                        return e.xp();
                    })
                    .orElse(null);
        }

        return null;
    }

    public List<XpIndexEntryDto> getAllEntries() {
        return List.copyOf(allEntries);
    }

    private Integer readRewardUec(JsonNode m) {
        for (String field : List.of("rewardUEC", "rewardUec", "rewardUecMax", "rewardMax")) {
            JsonNode node = m.path(field);
            if (!node.isMissingNode() && !node.isNull() && node.asInt(0) > 0) return node.asInt();
        }
        return null;
    }

    private Set<String> tokenize(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase().split("[^a-z]+")) {
            if (w.length() >= 3) words.add(w);
        }
        return words;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
