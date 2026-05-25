package com.janne6565.haulingcompanionapi.controller.v1.implementation;

import com.janne6565.haulingcompanionapi.controller.v1.schema.OptimizeApi;
import com.janne6565.haulingcompanionapi.model.action.OptimizeRequest;
import com.janne6565.haulingcompanionapi.model.core.OptimizeResultDto;
import com.janne6565.haulingcompanionapi.services.optimizer.OptimizerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OptimizeController implements OptimizeApi {

    private final OptimizerService optimizerService;

    @Override
    public ResponseEntity<OptimizeResultDto> optimize(OptimizeRequest request) {
        return ResponseEntity.ok(optimizerService.optimize(request));
    }
}
