-- ============================================================
-- V2: ТОЛЬКО дельта к V1 — инфраструктура импорта (UC-02/UC-03).
-- Никаких CREATE TABLE users/transactions и т.п. — они уже есть в V1.
-- ============================================================

-- 1) Результат каждой синхронизации (плановой или ручной)
CREATE TABLE transaction_sync_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_connection_id  UUID NOT NULL,
    user_id             UUID NOT NULL,
    period_month        DATE NOT NULL,
    sync_type           VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (sync_type IN ('SCHEDULED', 'MANUAL')),
    sync_status         VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                        CHECK (sync_status IN ('RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED')),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMPTZ,
    pages_fetched       INTEGER NOT NULL DEFAULT 0,
    records_fetched     INTEGER NOT NULL DEFAULT 0,
    records_inserted    INTEGER NOT NULL DEFAULT 0,
    records_updated     INTEGER NOT NULL DEFAULT 0,
    records_unchanged   INTEGER NOT NULL DEFAULT 0,
    records_invalid     INTEGER NOT NULL DEFAULT 0,
    records_failed      INTEGER NOT NULL DEFAULT 0,
    last_cursor         TEXT,
    error_details       JSONB NOT NULL DEFAULT '[]'::JSONB,
    CONSTRAINT fk_sync_runs_connection
        FOREIGN KEY (bank_connection_id, user_id)
        REFERENCES bank_connections (id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_sync_runs_month_start
        CHECK (period_month = DATE_TRUNC('month', period_month)::DATE)
);

-- не более одного активного прогона на (подключение, месяц)
CREATE UNIQUE INDEX uq_sync_runs_one_running
    ON transaction_sync_runs (bank_connection_id, period_month)
    WHERE sync_status = 'RUNNING';

CREATE INDEX idx_sync_runs_connection_month
    ON transaction_sync_runs (bank_connection_id, period_month, started_at DESC);

-- 2) Реестр внешних ключей: уникальность (источник + внешний пользователь
--    = bank_connection_id, внешний ID операции). Таблица НЕ партиционирована,
--    поэтому уникальность глобальная, а не в рамках occurred_at.
CREATE TABLE transaction_external_keys (
    bank_connection_id      UUID NOT NULL,
    external_transaction_id VARCHAR(255) NOT NULL,
    user_id                 UUID NOT NULL,
    transaction_id          UUID NOT NULL,
    transaction_occurred_at TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_transaction_external_keys
        PRIMARY KEY (bank_connection_id, external_transaction_id),
    CONSTRAINT fk_external_keys_connection
        FOREIGN KEY (bank_connection_id, user_id)
        REFERENCES bank_connections (id, user_id) ON DELETE CASCADE,
    -- DEFERRABLE: ключ вставляется ДО транзакции в одной tx, проверка FK — на commit
    CONSTRAINT fk_external_keys_transaction
        FOREIGN KEY (transaction_id, user_id, transaction_occurred_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON UPDATE CASCADE ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_external_keys_transaction
    ON transaction_external_keys (transaction_id, transaction_occurred_at);

-- 3) Банк может исправить occurred_at у дубля -> строка переезжает между
--    партициями. Дочерние FK без ON UPDATE CASCADE заблокируют UPDATE —
--    пересоздаём их с каскадом (имена констрейнтов из V1).
ALTER TABLE transaction_category_history
    DROP CONSTRAINT fk_category_history_transaction,
    ADD CONSTRAINT fk_category_history_transaction
        FOREIGN KEY (transaction_id, user_id, transaction_occurred_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE savings_goal_contributions
    DROP CONSTRAINT fk_contributions_transaction,
    ADD CONSTRAINT fk_contributions_transaction
        FOREIGN KEY (transaction_id, user_id, transaction_occurred_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON UPDATE CASCADE ON DELETE SET NULL (transaction_id, transaction_occurred_at);

ALTER TABLE notifications
    DROP CONSTRAINT fk_notifications_transaction,
    ADD CONSTRAINT fk_notifications_transaction
        FOREIGN KEY (related_transaction_id, user_id, related_transaction_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON UPDATE CASCADE ON DELETE SET NULL (related_transaction_id, related_transaction_at);
