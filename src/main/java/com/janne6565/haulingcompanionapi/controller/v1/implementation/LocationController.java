package com.janne6565.haulingcompanionapi.controller.v1.implementation;

import com.janne6565.haulingcompanionapi.controller.v1.schema.LocationApi;
import com.janne6565.haulingcompanionapi.model.core.LocationDto;
import com.janne6565.haulingcompanionapi.services.starmap.StarmapService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LocationController implements LocationApi {

    private final StarmapService starmapService;

    @Override
    public ResponseEntity<List<LocationDto>> search(String q) {
        return ResponseEntity.ok(starmapService.searchLocations(q));
    }
}
