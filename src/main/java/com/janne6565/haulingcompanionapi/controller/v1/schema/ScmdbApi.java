package com.janne6565.haulingcompanionapi.controller.v1.schema;

import com.janne6565.haulingcompanionapi.model.core.XpIndexEntryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "SCMDB", description = "Star Citizen mission database XP index")
@RequestMapping("/v1/scmdb")
public interface ScmdbApi {

    @Operation(summary = "Get the full XP lookup index")
    @ApiResponse(responseCode = "200", description = "XP index returned")
    @GetMapping("/xp-index")
    ResponseEntity<List<XpIndexEntryDto>> getXpIndex();
}
