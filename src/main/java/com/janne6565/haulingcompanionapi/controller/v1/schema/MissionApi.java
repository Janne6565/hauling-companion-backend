package com.janne6565.haulingcompanionapi.controller.v1.schema;

import com.janne6565.haulingcompanionapi.model.core.ParsedMissionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Missions", description = "Mission screenshot parsing")
@RequestMapping("/v1/missions")
public interface MissionApi {

    @Operation(summary = "Parse a mission screenshot via OCR using configured bounding-box regions")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Mission parsed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid or missing image / regions")
    })
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ParsedMissionDto> parseMission(
            @RequestParam("image") MultipartFile image,
            @RequestParam("regions") String regionsJson);
}
