package com.janne6565.haulingcompanionapi.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tesseract")
public class TesseractProperties {
    private String dataPath;
    private String language = "eng";
}
