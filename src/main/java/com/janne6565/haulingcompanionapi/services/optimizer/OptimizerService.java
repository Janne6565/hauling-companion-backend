package com.janne6565.haulingcompanionapi.services.optimizer;

import com.janne6565.haulingcompanionapi.model.action.OptimizeRequest;
import com.janne6565.haulingcompanionapi.model.core.MissionLegDto;
import com.janne6565.haulingcompanionapi.model.core.OptimizeResultDto;
import com.janne6565.haulingcompanionapi.model.core.ParsedMissionDto;
import com.janne6565.haulingcompanionapi.model.core.StopDto;
import com.janne6565.haulingcompanionapi.model.core.StopItemDto;
import com.janne6565.haulingcompanionapi.services.starmap.StarmapService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizerService {

    private final StarmapService starmapService;

    public OptimizeResultDto optimize(OptimizeRequest request) {
        List<ParsedMissionDto> missions = request.missions();
        int n = missions.size();
        int capacity = ShipRegistry.capacityFor(request.ship());
        Set<Integer> forceIn = toSet(request.forceInclude());
        Set<Integer> forceOut = toSet(request.forceExclude());
        boolean byProfit = "PROFIT".equalsIgnoreCase(request.goal());

        if (!request.allowInterstellar()) {
            String currentSystem = starmapService.systemOf(request.currentLocation());
            if (currentSystem != null) {
                for (int i = 0; i < n; i++) {
                    if (!forceOut.contains(i) && isInterstellar(missions.get(i), currentSystem)) {
                        forceOut.add(i);
                    }
                }
            } else {
                log.warn("Optimize: allowInterstellar=false but could not resolve system for '{}' — filter skipped", request.currentLocation());
            }
        }

        List<Integer> best = selectBestSubset(missions, n, capacity, request.maxMissions(), request.maxStops(), forceIn, forceOut, byProfit);

        if (best.isEmpty()) {
            return OptimizeResultDto.builder()
                    .selectedMissionIndices(List.of())
                    .stops(List.of())
                    .stopCount(0)
                    .totalRewardUec(0)
                    .totalXp(0)
                    .build();
        }

        List<StopDto> stops = buildStops(missions, best, request.currentLocation());
        int totalReward = best.stream().mapToInt(i -> nvl(missions.get(i).rewardUec())).sum();
        int totalXp = best.stream().mapToInt(i -> nvl(missions.get(i).xp())).sum();

        log.info("Optimize: selected {} missions, {} stops, {}aUEC, {}xp", best.size(), stops.size(), totalReward, totalXp);

        return OptimizeResultDto.builder()
                .selectedMissionIndices(best)
                .stops(stops)
                .stopCount(stops.size())
                .totalRewardUec(totalReward)
                .totalXp(totalXp)
                .build();
    }

    private boolean isInterstellar(ParsedMissionDto mission, String currentSystem) {
        for (MissionLegDto leg : mission.pickups()) {
            if (leg.location() == null) continue;
            String sys = starmapService.systemOf(leg.location());
            // null means the wiki doesn't know this location — treat as foreign when
            // interstellar is disabled, since the wiki reliably covers known in-system locations
            if (sys == null || !sys.equalsIgnoreCase(currentSystem)) return true;
        }
        for (MissionLegDto leg : mission.deliveries()) {
            if (leg.location() == null) continue;
            String sys = starmapService.systemOf(leg.location());
            if (sys == null || !sys.equalsIgnoreCase(currentSystem)) return true;
        }
        return false;
    }

    // ── Subset selection ─────────────────────────────────────────────────────────

    /**
     * Single-pass over all 2^n subsets. Scores each valid subset by a penalized total:
     *   score = totalValue - stopPenalty * stopCount
     * stopPenalty scales with the average eligible-mission value so the threshold is
     * self-calibrating across rookie (low-value) and veteran (high-value) sessions.
     * Hard constraints: capacity, maxMissions, maxStops, forceIn/forceOut.
     */
    private List<Integer> selectBestSubset(
            List<ParsedMissionDto> missions,
            int n,
            int capacity,
            int maxMissions,
            int maxStops,
            Set<Integer> forceIn,
            Set<Integer> forceOut,
            boolean byProfit) {

        // A stop is "paid for" when the value it unlocks clears 25% of an average mission's payout.
        // Using the average of eligible missions keeps the threshold proportional to the session's value level.
        double avgEligibleValue = IntStream.range(0, n)
                .filter(i -> !forceOut.contains(i))
                .mapToDouble(i -> byProfit ? nvl(missions.get(i).rewardUec()) : nvl(missions.get(i).xp()))
                .filter(v -> v > 0)
                .average()
                .orElse(50_000.0);
        double stopPenalty = avgEligibleValue * 0.25;

        List<Integer> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int mask = 1; mask < (1 << n); mask++) {
            int count = Integer.bitCount(mask);
            if (count > maxMissions) continue;
            if (count < forceIn.size()) continue;

            List<Integer> subset = indicesOf(mask, n);
            if (!subset.containsAll(forceIn)) continue;
            if (subset.stream().anyMatch(forceOut::contains)) continue;

            int totalScu =
                    subset.stream()
                            .flatMap(i -> missions.get(i).deliveries().stream())
                            .mapToInt(d -> d.scu() != null ? d.scu() : 0)
                            .sum();
            if (capacity < Integer.MAX_VALUE && totalScu > capacity) continue;

            int stops = countUniqueLocations(missions, subset);
            if (stops > maxStops) continue;

            int value =
                    byProfit
                            ? subset.stream().mapToInt(i -> nvl(missions.get(i).rewardUec())).sum()
                            : subset.stream().mapToInt(i -> nvl(missions.get(i).xp())).sum();

            double score = value - stopPenalty * stops;
            if (score > bestScore) {
                bestScore = score;
                best = new ArrayList<>(subset);
            }
        }

        return best != null ? best : (forceIn.isEmpty() ? List.of() : new ArrayList<>(forceIn));
    }

    private int countUniqueLocations(List<ParsedMissionDto> missions, List<Integer> indices) {
        Set<String> locs = new HashSet<>();
        for (int i : indices) {
            missions.get(i).pickups().stream().map(MissionLegDto::location).filter(Objects::nonNull).forEach(locs::add);
            missions.get(i).deliveries().stream().map(MissionLegDto::location).filter(Objects::nonNull).forEach(locs::add);
        }
        return locs.size();
    }

    // ── Stop ordering ────────────────────────────────────────────────────────────

    private List<StopDto> buildStops(
            List<ParsedMissionDto> allMissions, List<Integer> selectedIndices, String startLocation) {

        // Build per-location maps
        Map<String, List<StopItemDto>> pickupsAt = new LinkedHashMap<>();
        Map<String, List<StopItemDto>> deliveriesAt = new LinkedHashMap<>();

        for (int mIdx : selectedIndices) {
            ParsedMissionDto m = allMissions.get(mIdx);
            for (MissionLegDto leg : m.pickups()) {
                if (leg.location() == null) continue;
                pickupsAt.computeIfAbsent(leg.location(), k -> new ArrayList<>())
                        .add(StopItemDto.builder().missionIndex(mIdx).cargoType(leg.cargoType()).scu(leg.scu()).build());
            }
            for (MissionLegDto leg : m.deliveries()) {
                if (leg.location() == null) continue;
                deliveriesAt.computeIfAbsent(leg.location(), k -> new ArrayList<>())
                        .add(StopItemDto.builder().missionIndex(mIdx).cargoType(leg.cargoType()).scu(leg.scu()).build());
            }
        }

        // Pre-warm position cache for all stop locations before any distance calculation.
        // Delivery-only stops are never visited in Phase 1, so without this their positions
        // would be resolved mid-routing — if the API call fails they'd fall back to DEFAULT_POS
        // and break the nearest-neighbour ordering.
        Set<String> allLocs = new LinkedHashSet<>(pickupsAt.keySet());
        allLocs.addAll(deliveriesAt.keySet());
        if (startLocation != null) allLocs.add(startLocation);
        starmapService.preloadPositions(allLocs);

        // For each mission: set of pickup locations that must be visited before delivery
        Map<Integer, Set<String>> missionPickupLocs = new HashMap<>();
        for (int mIdx : selectedIndices) {
            missionPickupLocs.put(
                    mIdx,
                    allMissions.get(mIdx).pickups().stream()
                            .map(MissionLegDto::location)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet()));
        }

        Set<String> visitedPickupLocs = new HashSet<>();
        List<StopDto> stops = new ArrayList<>();
        String current = startLocation;

        // Phase 1 — visit all pickup locations in nearest-neighbor order.
        // Do ready deliveries at the same stop when possible.
        Set<String> phase1 = new LinkedHashSet<>(pickupsAt.keySet());
        while (!phase1.isEmpty()) {
            String next = nearest(current, phase1);
            phase1.remove(next);
            visitedPickupLocs.add(next);

            List<StopItemDto> pickups = pickupsAt.getOrDefault(next, List.of());
            List<StopItemDto> deliveries =
                    deliveriesAt.getOrDefault(next, List.of()).stream()
                            .filter(item -> visitedPickupLocs.containsAll(
                                    missionPickupLocs.getOrDefault(item.missionIndex(), Set.of())))
                            .collect(Collectors.toList());

            stops.add(makeStop(next, pickups, deliveries, starmapService.distanceBetween(current, next)));
            current = next;
        }

        // Phase 2 — delivery-only locations (all pickups are guaranteed done by now)
        Set<String> phase2 = new LinkedHashSet<>(deliveriesAt.keySet());
        phase2.removeAll(pickupsAt.keySet());
        while (!phase2.isEmpty()) {
            String next = nearest(current, phase2);
            phase2.remove(next);

            List<StopItemDto> deliveries = deliveriesAt.getOrDefault(next, List.of());
            stops.add(makeStop(next, List.of(), deliveries, starmapService.distanceBetween(current, next)));
            current = next;
        }

        // Phase 3 — revisit pickup locations that had deferred deliveries (cross-delivery edge case)
        Set<StopItemDto> alreadyDelivered =
                stops.stream().flatMap(s -> s.dropoffs().stream()).collect(Collectors.toSet());
        for (String loc : pickupsAt.keySet()) {
            List<StopItemDto> missed =
                    deliveriesAt.getOrDefault(loc, List.of()).stream()
                            .filter(item -> !alreadyDelivered.contains(item))
                            .collect(Collectors.toList());
            if (!missed.isEmpty()) {
                stops.add(makeStop(loc, List.of(), missed, starmapService.distanceBetween(current, loc)));
                current = loc;
            }
        }

        return stops;
    }

    private StopDto makeStop(String location, List<StopItemDto> pickups, List<StopItemDto> deliveries, double distAu) {
        String type =
                !pickups.isEmpty() && !deliveries.isEmpty()
                        ? "PICKUP_DROPOFF"
                        : !pickups.isEmpty() ? "PICKUP" : "DROPOFF";
        return StopDto.builder()
                .location(location)
                .parentBody(starmapService.parentBodyOf(location))
                .stopType(type)
                .distanceAu(distAu)
                .distanceLabel(formatAu(distAu))
                .pickups(pickups)
                .dropoffs(deliveries)
                .build();
    }

    private String nearest(String from, Set<String> candidates) {
        return candidates.stream()
                .min(Comparator.comparingDouble(loc -> starmapService.distanceBetween(from, loc)))
                .orElseThrow();
    }

    private String formatAu(double au) {
        if (au < 0.005) return "Same area";
        if (au < 0.15) return String.format("%.0f Mm", au * 149_597.9);
        return String.format("%.2f AU", au);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static List<Integer> indicesOf(int mask, int n) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) list.add(i);
        return list;
    }

    private static Set<Integer> toSet(List<Integer> list) {
        return list == null ? new HashSet<>() : new HashSet<>(list);
    }

    private static int nvl(Integer v) {
        return v != null ? v : 0;
    }
}
