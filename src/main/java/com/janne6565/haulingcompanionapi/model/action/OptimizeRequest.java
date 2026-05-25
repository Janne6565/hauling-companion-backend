package com.janne6565.haulingcompanionapi.model.action;

import com.janne6565.haulingcompanionapi.model.core.ParsedMissionDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Builder;

@Builder
public record OptimizeRequest(
        @NotNull List<ParsedMissionDto> missions,
        @NotBlank String currentLocation,
        @NotBlank String ship,
        @NotBlank String goal,
        @Min(1) @Max(20) int maxMissions,
        @Min(1) @Max(30) int maxStops,
        List<Integer> forceInclude,
        List<Integer> forceExclude,
        boolean allowInterstellar) {}
