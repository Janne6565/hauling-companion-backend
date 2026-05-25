package com.janne6565.haulingcompanionapi.controller.v1.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janne6565.haulingcompanionapi.controller.v1.schema.MissionApi;
import com.janne6565.haulingcompanionapi.model.core.ParsedMissionDto;
import com.janne6565.haulingcompanionapi.model.core.RegionConfigDto;
import com.janne6565.haulingcompanionapi.services.ocr.OcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class MissionController implements MissionApi {

    private final OcrService ocrService;
    private final ObjectMapper objectMapper;

    @Override
    public ResponseEntity<ParsedMissionDto> parseMission(MultipartFile image, String regionsJson) {
        if (image == null || image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file is required");
        }
        RegionConfigDto regions;
        try {
            regions = objectMapper.readValue(regionsJson, RegionConfigDto.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid regions JSON: " + e.getMessage());
        }
        return ResponseEntity.ok(ocrService.parse(image, regions));
    }
}
