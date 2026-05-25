package com.janne6565.haulingcompanionapi.model.core;

import lombok.Builder;

@Builder(toBuilder = true)
public record MissionLegDto(String location, String body, Integer scu, String cargoType) {}
