package com.janne6565.haulingcompanionapi.controller.v1.implementation;

import com.janne6565.haulingcompanionapi.controller.v1.schema.ScmdbApi;
import com.janne6565.haulingcompanionapi.model.core.XpIndexEntryDto;
import com.janne6565.haulingcompanionapi.services.scmdb.ScmdbService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ScmdbController implements ScmdbApi {

    private final ScmdbService scmdbService;

    @Override
    public ResponseEntity<List<XpIndexEntryDto>> getXpIndex() {
        return ResponseEntity.ok(scmdbService.getAllEntries());
    }
}
