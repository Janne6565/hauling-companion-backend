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
import java.util.Iterator;
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

    /** Per-mission planning shape: pickups split into mandatory legs vs. "visit one of" choice groups. */
    private record MissionShape(
            List<MissionLegDto> mandatoryPickups, List<ChoiceGroup> choiceGroups, Set<String> deliveryLocs) {}

    /**
     * A set of interchangeable pickup locations for one material in one mission. The mission leaves the
     * per-location SCU unset, which we read as "this resource can be collected from any one of these
     * locations" — so the optimizer visits exactly one, chosen to minimise total stops.
     */
    private record ChoiceGroup(int missionIndex, String cargoType, List<MissionLegDto> candidates) {}

    /** Result of resolving choice groups for a subset: which location each group uses, and every visited stop. */
    private record Resolution(Map<ChoiceGroup, String> picks, Set<String> visited) {}

    public OptimizeResultDto optimize(OptimizeRequest request) {
        List<ParsedMissionDto> missions = request.missions();
        int n = missions.size();
        List<MissionShape> shapes = IntStream.range(0, n).mapToObj(i -> shapeOf(i, missions.get(i))).toList();

        int capacity = ShipRegistry.capacityFor(request.ship());
        Set<Integer> forceIn = toSet(request.forceInclude());
        Set<Integer> forceOut = toSet(request.forceExclude());
        boolean byProfit = "PROFIT".equalsIgnoreCase(request.goal());

        if (!request.allowInterstellar()) {
            String currentSystem = starmapService.systemOf(request.currentLocation());
            if (currentSystem != null) {
                for (int i = 0; i < n; i++) {
                    if (!forceOut.contains(i) && isInterstellar(shapes.get(i), currentSystem)) {
                        forceOut.add(i);
                    }
                }
            } else {
                log.warn("Optimize: allowInterstellar=false but could not resolve system for '{}' — filter skipped", request.currentLocation());
            }
        }

        List<Integer> best = selectBestSubset(missions, shapes, n, capacity, request.maxMissions(), request.maxStops(), forceIn, forceOut, byProfit);

        if (best.isEmpty()) {
            return OptimizeResultDto.builder()
                    .selectedMissionIndices(List.of())
                    .stops(List.of())
                    .stopCount(0)
                    .totalRewardUec(0)
                    .totalXp(0)
                    .build();
        }

        List<StopDto> stops = buildStops(missions, shapes, best, request.currentLocation());
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

    // ── Mission shaping ────────────────────────────────────────────────────────────

    /**
     * Splits a mission's pickups into mandatory legs and choice groups. A pickup with a fixed SCU is
     * always mandatory. Pickups with no SCU are grouped by material; a group of two or more becomes a
     * "visit one of" choice, while a lone candidate (or one whose material is unknown) stays mandatory
     * because there is no real alternative to fall back on.
     */
    private MissionShape shapeOf(int idx, ParsedMissionDto m) {
        List<MissionLegDto> mandatory = new ArrayList<>();
        Map<String, List<MissionLegDto>> unsetByCargo = new LinkedHashMap<>();

        for (MissionLegDto leg : safe(m.pickups())) {
            if (leg.location() == null) continue;
            String cargo = effectiveCargo(leg, m);
            if (isUnset(leg.scu()) && cargo != null && !cargo.isBlank()) {
                unsetByCargo.computeIfAbsent(cargo, k -> new ArrayList<>()).add(leg);
            } else {
                mandatory.add(leg);
            }
        }

        List<ChoiceGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<MissionLegDto>> e : unsetByCargo.entrySet()) {
            List<MissionLegDto> candidates = dedupByLocation(e.getValue());
            if (candidates.size() == 1) {
                mandatory.add(candidates.get(0));
            } else {
                groups.add(new ChoiceGroup(idx, e.getKey(), candidates));
            }
        }

        Set<String> deliveryLocs =
                safe(m.deliveries()).stream()
                        .map(MissionLegDto::location)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        return new MissionShape(mandatory, groups, deliveryLocs);
    }

    private boolean isInterstellar(MissionShape shape, String currentSystem) {
        for (MissionLegDto leg : shape.mandatoryPickups()) {
            if (leg.location() != null && isForeign(leg.location(), currentSystem)) return true;
        }
        for (String loc : shape.deliveryLocs()) {
            if (isForeign(loc, currentSystem)) return true;
        }
        // A choice group only forces interstellar travel when every alternative is out of system.
        for (ChoiceGroup g : shape.choiceGroups()) {
            boolean allForeign = g.candidates().stream().map(MissionLegDto::location).allMatch(l -> isForeign(l, currentSystem));
            if (allForeign) return true;
        }
        return false;
    }

    private boolean isForeign(String location, String currentSystem) {
        String sys = starmapService.systemOf(location);
        // null means the wiki doesn't know this location — treat as foreign when interstellar is
        // disabled, since the wiki reliably covers known in-system locations.
        return sys == null || !sys.equalsIgnoreCase(currentSystem);
    }

    // ── Choice-group resolution ──────────────────────────────────────────────────────

    /**
     * Picks one location per choice group for the given subset, preferring locations that are already
     * being visited (a mandatory pickup, a delivery, or a stop chosen for another group) so a choice
     * costs zero extra stops whenever possible. Remaining groups are covered greedily by the location
     * that satisfies the most of them at once. With {@code useDistance}, ties between equally useful new
     * locations break toward the one nearest an already-visited stop; otherwise they break by name so the
     * result is deterministic without touching the starmap during subset scoring.
     */
    private Resolution resolve(List<MissionShape> shapes, List<Integer> subset, boolean useDistance, String start) {
        Set<String> visited = new HashSet<>();
        List<ChoiceGroup> groups = new ArrayList<>();
        for (int i : subset) {
            MissionShape s = shapes.get(i);
            for (MissionLegDto leg : s.mandatoryPickups()) {
                if (leg.location() != null) visited.add(leg.location());
            }
            visited.addAll(s.deliveryLocs());
            groups.addAll(s.choiceGroups());
        }

        Map<ChoiceGroup, String> picks = new HashMap<>();
        List<ChoiceGroup> unresolved = new ArrayList<>(groups);

        while (true) {
            boolean freed = false;
            for (Iterator<ChoiceGroup> it = unresolved.iterator(); it.hasNext(); ) {
                ChoiceGroup g = it.next();
                String hit = g.candidates().stream().map(MissionLegDto::location).filter(visited::contains).findFirst().orElse(null);
                if (hit != null) {
                    picks.put(g, hit);
                    it.remove();
                    freed = true;
                }
            }
            if (unresolved.isEmpty()) break;
            if (freed) continue;

            Map<String, Integer> coverage = new HashMap<>();
            for (ChoiceGroup g : unresolved) {
                for (MissionLegDto c : g.candidates()) coverage.merge(c.location(), 1, Integer::sum);
            }
            visited.add(bestNewLocation(coverage, useDistance, start, visited));
        }

        return new Resolution(picks, visited);
    }

    /** Highest-coverage location wins; ties break by distance to the nearest visited stop, else by name. */
    private String bestNewLocation(Map<String, Integer> coverage, boolean useDistance, String start, Set<String> visited) {
        String best = null;
        int bestCoverage = -1;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> e : coverage.entrySet()) {
            String loc = e.getKey();
            int cov = e.getValue();
            double dist = useDistance ? nearestDistance(loc, visited, start) : 0;
            boolean better;
            if (cov != bestCoverage) {
                better = cov > bestCoverage;
            } else if (useDistance) {
                better = dist < bestDistance;
            } else {
                better = best == null || loc.compareTo(best) < 0;
            }
            if (better) {
                best = loc;
                bestCoverage = cov;
                bestDistance = dist;
            }
        }
        return best;
    }

    private double nearestDistance(String loc, Set<String> visited, String start) {
        if (!visited.isEmpty()) {
            return visited.stream().mapToDouble(v -> starmapService.distanceBetween(v, loc)).min().orElse(Double.MAX_VALUE);
        }
        return start != null ? starmapService.distanceBetween(start, loc) : 0;
    }

    // ── Subset selection ─────────────────────────────────────────────────────────

    /**
     * Single-pass over all 2^n subsets. Scores each valid subset by a penalized total:
     *   score = totalValue - stopPenalty * stopCount
     * stopPenalty scales with the average eligible-mission value so the threshold is
     * self-calibrating across rookie (low-value) and veteran (high-value) sessions.
     * The stop count reflects resolved choice groups, so a subset whose alternatives collapse onto
     * already-visited locations is scored as the cheaper route it actually is.
     * Hard constraints: capacity, maxMissions, maxStops, forceIn/forceOut.
     */
    private List<Integer> selectBestSubset(
            List<ParsedMissionDto> missions,
            List<MissionShape> shapes,
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

            int stops = resolve(shapes, subset, false, null).visited().size();
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

    // ── Stop ordering ────────────────────────────────────────────────────────────

    private List<StopDto> buildStops(
            List<ParsedMissionDto> allMissions, List<MissionShape> shapes, List<Integer> selectedIndices, String startLocation) {

        // Pre-warm the position cache for everything we might touch — including unchosen choice-group
        // alternatives, since resolution below uses distances to break ties between them.
        Set<String> allLocs = new LinkedHashSet<>();
        if (startLocation != null) allLocs.add(startLocation);
        for (int i : selectedIndices) {
            MissionShape s = shapes.get(i);
            s.mandatoryPickups().forEach(l -> {
                if (l.location() != null) allLocs.add(l.location());
            });
            allLocs.addAll(s.deliveryLocs());
            s.choiceGroups().forEach(g -> g.candidates().forEach(l -> allLocs.add(l.location())));
        }
        starmapService.preloadPositions(allLocs);

        Resolution resolution = resolve(shapes, selectedIndices, true, startLocation);

        // Concrete pickup legs to visit per mission: mandatory legs plus the one location chosen per group.
        // Track which (missionIdx, location, cargoType) tuples came from choice groups so we can mark them optional.
        Set<String> choiceGroupKeys = new HashSet<>();
        Map<Integer, List<MissionLegDto>> resolvedPickups = new HashMap<>();
        for (int mIdx : selectedIndices) {
            MissionShape s = shapes.get(mIdx);
            List<MissionLegDto> legs = new ArrayList<>(s.mandatoryPickups());
            for (ChoiceGroup g : s.choiceGroups()) {
                String loc = resolution.picks().get(g);
                g.candidates().stream().filter(c -> loc.equals(c.location())).findFirst().ifPresent(leg -> {
                    legs.add(leg);
                    if (leg.location() != null) {
                        choiceGroupKeys.add(mIdx + ":" + leg.location() + ":" + leg.cargoType());
                    }
                });
            }
            resolvedPickups.put(mIdx, legs);
        }

        // Build per-location maps from the resolved pickups (and the missions' deliveries).
        Map<String, List<StopItemDto>> pickupsAt = new LinkedHashMap<>();
        Map<String, List<StopItemDto>> deliveriesAt = new LinkedHashMap<>();

        for (int mIdx : selectedIndices) {
            for (MissionLegDto leg : resolvedPickups.get(mIdx)) {
                if (leg.location() == null) continue;
                boolean isOptional = choiceGroupKeys.contains(mIdx + ":" + leg.location() + ":" + leg.cargoType());
                pickupsAt.computeIfAbsent(leg.location(), k -> new ArrayList<>())
                        .add(StopItemDto.builder().missionIndex(mIdx).cargoType(leg.cargoType()).scu(leg.scu()).optional(isOptional).build());
            }
            for (MissionLegDto leg : allMissions.get(mIdx).deliveries()) {
                if (leg.location() == null) continue;
                deliveriesAt.computeIfAbsent(leg.location(), k -> new ArrayList<>())
                        .add(StopItemDto.builder().missionIndex(mIdx).cargoType(leg.cargoType()).scu(leg.scu()).build());
            }
        }

        // For each mission: set of pickup locations that must be visited before delivery.
        Map<Integer, Set<String>> missionPickupLocs = new HashMap<>();
        for (int mIdx : selectedIndices) {
            missionPickupLocs.put(
                    mIdx,
                    resolvedPickups.get(mIdx).stream()
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

    private static boolean isUnset(Integer scu) {
        return scu == null || scu <= 0;
    }

    private static String effectiveCargo(MissionLegDto leg, ParsedMissionDto mission) {
        if (leg.cargoType() != null && !leg.cargoType().isBlank()) return leg.cargoType();
        return mission.cargoType();
    }

    private static List<MissionLegDto> dedupByLocation(List<MissionLegDto> legs) {
        Map<String, MissionLegDto> byLocation = new LinkedHashMap<>();
        for (MissionLegDto leg : legs) byLocation.putIfAbsent(leg.location(), leg);
        return new ArrayList<>(byLocation.values());
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

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
