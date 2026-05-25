package com.janne6565.haulingcompanionapi.configuration;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {
    private String apiKey;
    private List<String> models = List.of("gemini-2.5-flash");
}
