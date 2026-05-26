package com.janne6565.haulingcompanionapi.model.core;

import lombok.Builder;

@Builder
public record StopItemDto(int missionIndex, String cargoType, Integer scu, boolean optional) {}
