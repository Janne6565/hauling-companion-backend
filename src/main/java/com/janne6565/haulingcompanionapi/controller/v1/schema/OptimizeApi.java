package com.janne6565.haulingcompanionapi.controller.v1.schema;

import com.janne6565.haulingcompanionapi.model.action.OptimizeRequest;
import com.janne6565.haulingcompanionapi.model.core.OptimizeResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Optimize", description = "Mission route optimization")
@RequestMapping("/v1/optimize")
public interface OptimizeApi {

    @Operation(summary = "Select the best mission subset and produce an ordered stop plan")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Optimization successful"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping
    ResponseEntity<OptimizeResultDto> optimize(@Valid @RequestBody OptimizeRequest request);
}
