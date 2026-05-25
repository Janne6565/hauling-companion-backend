package com.janne6565.haulingcompanionapi.controller.v1.schema;

import com.janne6565.haulingcompanionapi.model.core.LocationDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Locations", description = "Star Citizen location search (proxies wiki API)")
@RequestMapping("/v1/locations")
public interface LocationApi {

    @Operation(summary = "Search locations by partial name")
    @GetMapping("/search")
    ResponseEntity<List<LocationDto>> search(@RequestParam String q);
}
