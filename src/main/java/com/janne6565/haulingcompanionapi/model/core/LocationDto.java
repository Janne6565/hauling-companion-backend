package com.janne6565.haulingcompanionapi.model.core;

import lombok.Builder;

@Builder
public record LocationDto(String name, String parentBody, String system) {}
