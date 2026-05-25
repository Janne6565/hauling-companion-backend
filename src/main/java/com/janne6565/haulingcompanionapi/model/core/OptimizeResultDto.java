package com.janne6565.haulingcompanionapi.model.core;

import java.util.List;
import lombok.Builder;

@Builder
public record OptimizeResultDto(
        List<Integer> selectedMissionIndices,
        List<StopDto> stops,
        int stopCount,
        int totalRewardUec,
        int totalXp) {}
