package org.example.finance;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionSyncService {
    private final JdbcClient jdbc;
    private final SameBankClient client;
    private final TransactionValidator validator;
    private final TransactionImportService importer;
    private final JsonMapper json = JsonMapper.builder().build();   // Jackson 3 (Boot 4)

    public TransactionSyncService(JdbcClient jdbc, SameBankClient client,
                                  TransactionValidator validator, TransactionImportService importer) {
        this.jdbc = jdbc; this.client = client; this.validator = validator; this.importer = importer;
    }

    public record SyncResult(UUID runId, String status, int fetched, int inserted,
                             int updated, int unchanged, int invalid, int failed) {}

    private record Conn(UUID userId, String externalAccountId) {}

    private static final class Stats {
        int pages, fetched, inserted, updated, unchanged, invalid, failed;
    }

    public Optional<SyncResult> sync(UUID connectionId, YearMonth month, String syncType) {
        Conn conn = jdbc.sql("""
                SELECT user_id, external_account_id FROM bank_connections
                WHERE id = :id AND provider_code = 'SAME_BANK'
                """)
                .param("id", connectionId)
                .query((rs, n) -> new Conn(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SAME_BANK connection not found"));

        UUID runId = UUID.randomUUID();
        int started = jdbc.sql("""
                INSERT INTO transaction_sync_runs (id, bank_connection_id, user_id, period_month, sync_type)
                VALUES (:id, :conn, :user, :month, :type)
                ON CONFLICT (bank_connection_id, period_month) WHERE sync_status = 'RUNNING' DO NOTHING
                """)
                .param("id", runId).param("conn", connectionId).param("user", conn.userId())
                .param("month", month.atDay(1)).param("type", syncType)
                .update();
        if (started == 0) return Optional.empty();    // параллельный прогон уже идёт

        Stats s = new Stats();
        List<Map<String, Object>> errors = new ArrayList<>();
        String cursor = null;
        String status;
        try {
            while (true) {
                var page = client.fetchPage(conn.externalAccountId(), month, cursor);
                s.pages++;
                for (var t : page.items()) {
                    s.fetched++;
                    var problems = validator.validate(t, month);
                    if (!problems.isEmpty()) {
                        s.invalid++;
                        errors.add(Map.of("externalId", String.valueOf(t.id()), "problems", problems));
                        continue;
                    }
                    try {
                        switch (importer.importOne(conn.userId(), connectionId, t)) {
                            case INSERTED -> s.inserted++;
                            case UPDATED -> s.updated++;
                            case UNCHANGED -> s.unchanged++;
                        }
                    } catch (Exception e) {
                        s.failed++;
                        errors.add(Map.of("externalId", String.valueOf(t.id()), "error", String.valueOf(e.getMessage())));
                    }
                }
                cursor = page.nextCursor();
                if (cursor == null) break;
            }
            status = (s.invalid + s.failed == 0) ? "SUCCESS"
                    : (s.inserted + s.updated + s.unchanged > 0 ? "PARTIAL_SUCCESS" : "FAILED");
        } catch (Exception e) {                        // обрыв на середине: сохранённые страницы остаются
            errors.add(Map.of("pageError", String.valueOf(e.getMessage()), "cursor", String.valueOf(cursor)));
            status = (s.inserted + s.updated + s.unchanged > 0) ? "PARTIAL_SUCCESS" : "FAILED";
        }

        jdbc.sql("""
                UPDATE transaction_sync_runs
                SET sync_status = :status, finished_at = CURRENT_TIMESTAMP, pages_fetched = :pages,
                    records_fetched = :fetched, records_inserted = :inserted, records_updated = :updated,
                    records_unchanged = :unchanged, records_invalid = :invalid, records_failed = :failed,
                    last_cursor = :cursor, error_details = CAST(:errors AS jsonb)
                WHERE id = :id
                """)
                .param("status", status).param("pages", s.pages).param("fetched", s.fetched)
                .param("inserted", s.inserted).param("updated", s.updated).param("unchanged", s.unchanged)
                .param("invalid", s.invalid).param("failed", s.failed)
                .param("cursor", cursor).param("errors", json.writeValueAsString(errors))
                .param("id", runId)
                .update();

        if (!"FAILED".equals(status)) {
            jdbc.sql("""
                    UPDATE bank_connections
                    SET last_synced_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id
                    """).param("id", connectionId).update();
        }
        return Optional.of(new SyncResult(runId, status, s.fetched, s.inserted, s.updated,
                s.unchanged, s.invalid, s.failed));
    }
}
