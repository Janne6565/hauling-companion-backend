package com.janne6565.haulingcompanionapi.model.core;

import java.util.List;
import lombok.Builder;

@Builder
public record ParsedMissionDto(
        String title,
        Integer rewardUec,
        Integer xp,
        String cargoType,
        int orderCount,
        List<MissionLegDto> pickups,
        List<MissionLegDto> deliveries,
        String rawOcrText) {}
