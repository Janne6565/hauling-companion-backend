package com.janne6565.haulingcompanionapi.services.starmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janne6565.haulingcompanionapi.model.core.LocationDto;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class StarmapService {

    private static final String WIKI_API = "https://api.star-citizen.wiki/api";
    private static final double[] DEFAULT_POS = {1.5, 0.0};
    private static final double MOON_ORBIT_FACTOR = 0.05;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /** body name / slug (lowercase) → 2D position {x, y} in AU */
    private final Map<String, double[]> bodyPositions = new ConcurrentHashMap<>();

    /** location name (lowercase) → parent body name (lowercase) */
    private final Map<String, String> locationBodyCache = new ConcurrentHashMap<>();

    /** location name (lowercase) → parent body display name (original case from API) */
    private final Map<String, String> locationParentDisplay = new ConcurrentHashMap<>();

    /** location name (lowercase) → star system name (as returned by the API, e.g. "Stanton System") */
    private final Map<String, String> locationSystemCache = new ConcurrentHashMap<>();

    // ── Internal model ────────────────────────────────────────────────────────

    private record CelBody(int id, String name, String type, double distance, double longitude, int parentId) {}

    // ── Startup ───────────────────────────────────────────────────────────────

    @EventListener(ApplicationStartedEvent.class)
    public void loadCelestialObjects() {
        List.of("stanton", "pyro").forEach(this::loadSystem);
    }

    private void loadSystem(String systemSlug) {
        log.info("Loading {} celestial objects from wiki API…", systemSlug);
        try {
            String json =
                    webClient
                            .get()
                            .uri(WIKI_API + "/starsystems/" + systemSlug + "?include=celestialObjects")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

            JsonNode root = objectMapper.readTree(json);
            JsonNode objects = root.path("data").path("celestial_objects");
            if (objects.isMissingNode() || objects.isEmpty()) {
                objects = root.path("data").path("celestialObjects");
            }

            // Pass 1 – collect all STAR/PLANET/SATELLITE bodies, including those with null names
            Map<Integer, CelBody> byId = new HashMap<>();
            for (JsonNode o : objects) {
                int id = o.path("id").asInt(-1);
                if (id == -1) continue;
                String type = o.path("type").asText("");
                if (!type.equals("STAR") && !type.equals("PLANET") && !type.equals("SATELLITE")) continue;
                String name = o.path("name").asText(null);
                if (name != null && name.isBlank()) name = null;
                byId.put(
                        id,
                        new CelBody(
                                id,
                                name,
                                type,
                                o.path("distance").asDouble(0),
                                o.path("longitude").asDouble(0),
                                o.path("parent_id").asInt(-1)));
            }

            // Pass 1.5 – assign designations to null-named star and planets so that
            // the locations API's parent names (e.g. "Pyro I", "Pyro IV") resolve correctly
            assignDesignations(byId, systemSlug);

            // Pass 2 – compute 2D positions and register under name + slug (skip still-unnamed).
            // Also register each body in locationSystemCache so that planet/moon names (which
            // don't appear in the locations API) still resolve correctly in systemOf().
            String systemLabel = Character.toUpperCase(systemSlug.charAt(0)) + systemSlug.substring(1) + " System";
            for (CelBody body : byId.values()) {
                if (body.name() == null) continue;
                double[] pos = computePosition(body, byId);
                String nameLower = body.name().toLowerCase();
                String slug = toSlug(body.name());
                bodyPositions.put(nameLower, pos);
                bodyPositions.put(slug, pos);
                locationSystemCache.put(nameLower, systemLabel);
                locationSystemCache.put(slug, systemLabel);
            }

            log.info("Loaded {} celestial bodies from {} for distance calculation", byId.size(), systemSlug);
        } catch (Exception e) {
            log.warn("Failed to load celestial objects for {}: {}", systemSlug, e.getMessage());
        }
    }

    private void assignDesignations(Map<Integer, CelBody> byId, String systemSlug) {
        String sysDisplay = Character.toUpperCase(systemSlug.charAt(0)) + systemSlug.substring(1);

        // Name the star after the system if the API left it null
        CelBody star = byId.values().stream().filter(b -> "STAR".equals(b.type())).findFirst().orElse(null);
        int starId = -1;
        if (star != null) {
            starId = star.id();
            if (star.name() == null) {
                byId.put(starId, new CelBody(starId, sysDisplay, "STAR",
                        star.distance(), star.longitude(), star.parentId()));
            }
        }

        // Assign ordinal designations ("Pyro I", "Pyro IV", …) to unnamed planets
        // orbiting the star, in ascending distance order
        final int finalStarId = starId;
        List<CelBody> allPlanets = byId.values().stream()
                .filter(b -> "PLANET".equals(b.type()) && b.parentId() == finalStarId)
                .sorted(Comparator.comparingDouble(CelBody::distance))
                .collect(Collectors.toList());

        for (int i = 0; i < allPlanets.size(); i++) {
            CelBody planet = allPlanets.get(i);
            if (planet.name() == null) {
                String designation = sysDisplay + " " + toRoman(i + 1);
                byId.put(planet.id(), new CelBody(planet.id(), designation, "PLANET",
                        planet.distance(), planet.longitude(), planet.parentId()));
            }
        }
    }

    private static String toRoman(int n) {
        if (n <= 0 || n > 20) return String.valueOf(n);
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
                             "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};
        return numerals[n - 1];
    }

    private double[] computePosition(CelBody body, Map<Integer, CelBody> byId) {
        if ("STAR".equals(body.type())) return new double[] {0, 0};

        double lon = Math.toRadians(body.longitude());

        if ("PLANET".equals(body.type())) {
            return new double[] {body.distance() * Math.cos(lon), body.distance() * Math.sin(lon)};
        }

        // SATELLITE — orbit around parent planet
        CelBody parent = byId.get(body.parentId());
        if (parent == null) return DEFAULT_POS.clone();

        double parentLon = Math.toRadians(parent.longitude());
        double px = parent.distance() * Math.cos(parentLon);
        double py = parent.distance() * Math.sin(parentLon);
        double orbitR = Math.max(parent.distance() * MOON_ORBIT_FACTOR, 0.03);
        return new double[] {px + orbitR * Math.cos(lon), py + orbitR * Math.sin(lon)};
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<LocationDto> searchLocations(String query) {
        if (query == null || query.isBlank()) return List.of();
        try {
            String enc = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = URI.create(WIKI_API + "/locations?filter%5Bname%5D=" + enc + "&page%5Bsize%5D=12");
            String json = webClient.get().uri(uri).retrieve().bodyToMono(String.class).block();
            JsonNode data = objectMapper.readTree(json).path("data");

            List<LocationDto> results = new ArrayList<>();
            for (JsonNode loc : data) {
                String name = loc.path("name").asText(null);
                if (name == null) continue;
                String parentName = loc.path("parent").path("name").asText(null);
                String system = loc.path("system").asText(null);
                results.add(LocationDto.builder().name(name).parentBody(parentName).system(system).build());
                // Warm the lookup caches
                if (parentName != null) {
                    locationBodyCache.put(name.toLowerCase(), parentName.toLowerCase());
                    locationParentDisplay.put(name.toLowerCase(), parentName);
                }
                if (system != null) {
                    locationSystemCache.put(name.toLowerCase(), system);
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Location search '{}' failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    public void preloadPositions(Collection<String> locationNames) {
        locationNames.forEach(this::positionOf);
    }

    public String parentBodyOf(String locationName) {
        if (locationName == null) return null;
        String key = locationName.toLowerCase();
        String display = locationParentDisplay.get(key);
        if (display != null) return display;
        resolveParentBody(locationName); // populates both caches as a side effect
        return locationParentDisplay.get(key);
    }

    public String systemOf(String locationName) {
        if (locationName == null) return null;
        String key = locationName.toLowerCase();
        String cached = locationSystemCache.get(key);
        if (cached != null) return cached;
        resolveParentBody(locationName); // populates locationSystemCache as a side effect
        return locationSystemCache.get(key);
    }

    public double distanceBetween(String locationA, String locationB) {
        if (locationA == null || locationB == null) return 0;
        if (locationA.equalsIgnoreCase(locationB)) return 0;
        double[] a = positionOf(locationA);
        double[] b = positionOf(locationB);
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private double[] positionOf(String locationName) {
        String key = locationName.toLowerCase();

        // Is the name itself a known celestial body?
        double[] direct = bodyPositions.get(key);
        if (direct != null) return direct;

        // Resolve parent body (API lookup + cache)
        String parent = resolveParentBody(locationName);
        if (parent != null) {
            double[] pos = bodyPositions.get(parent);
            if (pos != null) return pos;

            // Parent is not a celestial body (e.g. a station) — go one level higher
            String grandParent = resolveParentBody(parent);
            if (grandParent != null) {
                pos = bodyPositions.get(grandParent);
                if (pos != null) {
                    bodyPositions.put(parent, pos);
                    return pos;
                }
            }
        }

        return DEFAULT_POS.clone();
    }

    private String resolveParentBody(String locationName) {
        String key = locationName.toLowerCase();
        String cached = locationBodyCache.get(key);
        if (cached != null) return cached;

        try {
            String enc = URLEncoder.encode(locationName.trim(), StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = URI.create(WIKI_API + "/locations?filter%5Bname%5D=" + enc + "&page%5Bsize%5D=5");
            String json = webClient.get().uri(uri).retrieve().bodyToMono(String.class).block();
            JsonNode data = objectMapper.readTree(json).path("data");

            for (JsonNode loc : data) {
                String name = loc.path("name").asText("");
                String parentName = loc.path("parent").path("name").asText(null);
                String system = loc.path("system").asText(null);
                if (parentName != null) {
                    locationBodyCache.put(name.toLowerCase(), parentName.toLowerCase());
                    locationParentDisplay.put(name.toLowerCase(), parentName);
                }
                if (system != null) {
                    locationSystemCache.put(name.toLowerCase(), system);
                }
                if (name.equalsIgnoreCase(locationName) && parentName != null) return parentName.toLowerCase();
            }

            // No exact match — use first result (OCR names are often shortened, e.g.
            // "Shallow Fields Station" vs wiki "CRU-L4 Shallow Fields Station")
            if (!data.isEmpty()) {
                JsonNode first = data.get(0);
                String parentName = first.path("parent").path("name").asText(null);
                String system = first.path("system").asText(null);
                if (parentName != null) {
                    locationBodyCache.put(key, parentName.toLowerCase());
                    locationParentDisplay.put(key, parentName);
                }
                if (system != null) {
                    locationSystemCache.put(key, system);
                }
                if (parentName != null) return parentName.toLowerCase();
            }
        } catch (Exception e) {
            log.debug("Parent body lookup for '{}' failed: {}", locationName, e.getMessage());
        }
        return null;
    }

    private static String toSlug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
