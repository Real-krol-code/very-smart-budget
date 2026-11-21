package org.example.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class SyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private final JdbcClient jdbc;
    private final TransactionSyncService sync;

    public SyncScheduler(JdbcClient jdbc, TransactionSyncService sync) { this.jdbc = jdbc; this.sync = sync; }

    @Scheduled(cron = "${sync.cron}")
    public void scheduledSync() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        var ids = jdbc.sql("""
                SELECT id FROM bank_connections
                WHERE provider_code = 'SAME_BANK' AND connection_status = 'ACTIVE'
                """).query((rs, n) -> rs.getObject(1, UUID.class)).list();
        for (UUID id : ids) {
            try {
                sync.sync(id, month, "SCHEDULED").ifPresentOrElse(
                        r -> log.info("sync {} {}: {}", id, month, r),
                        () -> log.info("sync {} {} skipped: already running", id, month));
            } catch (Exception e) {
                log.error("sync {} {} failed", id, month, e);   // одно подключение не валит остальные
            }
        }
    }
}
