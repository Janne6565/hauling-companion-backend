package com.janne6565.haulingcompanionapi.model.core;

import lombok.Builder;

@Builder
public record XpIndexEntryDto(String title, String cargoType, int orderCount, Integer rewardUec, int xp) {}
