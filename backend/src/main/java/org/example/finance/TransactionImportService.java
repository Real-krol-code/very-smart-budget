package org.example.finance;

import org.example.finance.BankDtos.BankTransactionDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class TransactionImportService {

    public enum Outcome { INSERTED, UPDATED, UNCHANGED }

    private record Existing(UUID id, OffsetDateTime occurredAt, BigDecimal amount, String currency,
                            String type, String merchant, String mcc, String description, String status) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public TransactionImportService(JdbcClient jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome importOne(UUID userId, UUID connectionId, BankTransactionDto t) {
        return tx.execute(status -> {
            ensurePartition(t.occurredAt());
            UUID newId = UUID.randomUUID();

            // 1) "столбим" внешний ключ: PK реестра — арбитр параллельных импортов
            int claimed = jdbc.sql("""
                    INSERT INTO transaction_external_keys
                        (bank_connection_id, external_transaction_id, user_id, transaction_id, transaction_occurred_at)
                    VALUES (:conn, :ext, :user, :tid, :ts)
                    ON CONFLICT (bank_connection_id, external_transaction_id) DO NOTHING
                    """)
                    .param("conn", connectionId).param("ext", t.id()).param("user", userId)
                    .param("tid", newId).param("ts", t.occurredAt())
                    .update();

            if (claimed == 1) {                       // 2a) новая операция
                jdbc.sql("""
                        INSERT INTO transactions
                            (id, user_id, bank_connection_id, external_transaction_id, amount, currency_code,
                             transaction_type, merchant_name, mcc, description, occurred_at,
                             transaction_source, transaction_status)
                        VALUES (:id, :user, :conn, :ext, :amount, :cur, :type, :merchant, :mcc, :descr, :ts, 'BANK', :status)
                        """)
                        .param("id", newId).param("user", userId).param("conn", connectionId)
                        .param("ext", t.id()).param("amount", t.amount()).param("cur", t.currency())
                        .param("type", t.type()).param("merchant", t.merchantName()).param("mcc", t.mcc())
                        .param("descr", t.description()).param("ts", t.occurredAt()).param("status", t.status())
                        .update();
                return Outcome.INSERTED;
            }

            // 2b) дубль: блокируем ключ и читаем текущие данные
            Existing ex = jdbc.sql("""
                    SELECT k.transaction_id, k.transaction_occurred_at,
                           t.amount, t.currency_code, t.transaction_type, t.merchant_name,
                           t.mcc, t.description, t.transaction_status
                    FROM transaction_external_keys k
                    JOIN transactions t
                      ON t.id = k.transaction_id AND t.user_id = k.user_id
                     AND t.occurred_at = k.transaction_occurred_at
                    WHERE k.bank_connection_id = :conn AND k.external_transaction_id = :ext
                    FOR UPDATE OF k
                    """)
                    .param("conn", connectionId).param("ext", t.id())
                    .query((rs, n) -> new Existing(
                            rs.getObject("transaction_id", UUID.class),
                            rs.getObject("transaction_occurred_at", OffsetDateTime.class),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency_code"),
                            rs.getString("transaction_type"),
                            rs.getString("merchant_name"),
                            rs.getString("mcc"),
                            rs.getString("description"),
                            rs.getString("transaction_status")))
                    .single();

            if (unchanged(ex, t)) return Outcome.UNCHANGED;

            ensurePartition(t.occurredAt());          // дата могла переехать в другой месяц
            jdbc.sql("""
                    UPDATE transactions
                    SET amount = :amount, currency_code = :cur, transaction_type = :type,
                        merchant_name = :merchant, mcc = :mcc, description = :descr,
                        occurred_at = :ts, transaction_status = :status,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :tid AND user_id = :user AND occurred_at = :oldTs
                    """)
                    .param("amount", t.amount()).param("cur", t.currency()).param("type", t.type())
                    .param("merchant", t.merchantName()).param("mcc", t.mcc()).param("descr", t.description())
                    .param("ts", t.occurredAt()).param("status", t.status())
                    .param("tid", ex.id()).param("user", userId).param("oldTs", ex.occurredAt())
                    .update();                        // реестр обновится сам по ON UPDATE CASCADE
            return Outcome.UPDATED;
        });
    }

    private void ensurePartition(OffsetDateTime ts) {
        // это SELECT функции, поэтому .query(), а не .update()
        jdbc.sql("SELECT ensure_transaction_partition(:ts)").param("ts", ts).query((rs, n) -> null);
    }

    private boolean unchanged(Existing e, BankTransactionDto t) {
        return e.amount().compareTo(t.amount()) == 0
                && Objects.equals(e.currency(), t.currency())
                && Objects.equals(e.type(), t.type())
                && Objects.equals(e.merchant(), t.merchantName())
                && Objects.equals(e.mcc(), t.mcc())
                && Objects.equals(e.description(), t.description())
                && e.occurredAt().toInstant().equals(t.occurredAt().toInstant())
                && Objects.equals(e.status(), t.status());
    }
}
