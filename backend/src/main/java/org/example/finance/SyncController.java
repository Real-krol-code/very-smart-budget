package org.example.finance;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/sync")
public class SyncController {
    private final TransactionSyncService sync;
    public SyncController(TransactionSyncService sync) { this.sync = sync; }

    @PostMapping("/bank-connections/{connectionId}")
    public ResponseEntity<?> run(@PathVariable UUID connectionId, @RequestParam String month) {
        YearMonth ym;
        try { ym = YearMonth.parse(month); }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        return sync.sync(connectionId, ym, "MANUAL")
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "sync already running for this connection and month")));
    }
}
