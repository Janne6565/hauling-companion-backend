package com.janne6565.haulingcompanionapi.model.core;

import java.util.List;
import lombok.Builder;

@Builder
public record StopDto(
        String location,
        String parentBody,
        String stopType,
        Double distanceAu,
        String distanceLabel,
        List<StopItemDto> pickups,
        List<StopItemDto> dropoffs) {}
