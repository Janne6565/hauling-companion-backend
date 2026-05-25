package com.janne6565.haulingcompanionapi.configuration;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hauler")
public class HaulerProperties {
    private String scmdbUrl;
    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:5174");
}
