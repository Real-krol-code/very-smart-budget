CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(320) NOT NULL,
    password_hash       TEXT NOT NULL,
    first_name          VARCHAR(100),
    last_name           VARCHAR(100),
    account_status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (account_status IN ('ACTIVE', 'BLOCKED', 'DELETED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));

CREATE TABLE bank_connections (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_code       VARCHAR(100) NOT NULL,
    external_account_id VARCHAR(255) NOT NULL,
    connection_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (connection_status IN ('PENDING', 'ACTIVE', 'ERROR', 'DISCONNECTED')),
    connected_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_synced_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bank_connections_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_bank_connections_external_account
        UNIQUE (user_id, provider_code, external_account_id)
);

CREATE TABLE categories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID REFERENCES users(id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    category_type       VARCHAR(10) NOT NULL
                        CHECK (category_type IN ('INCOME', 'EXPENSE')),
    is_system           BOOLEAN NOT NULL DEFAULT FALSE,
    icon                VARCHAR(100),
    color               VARCHAR(20),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_categories_owner
        CHECK ((is_system = TRUE AND user_id IS NULL)
            OR (is_system = FALSE AND user_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_categories_system_name_type
    ON categories (LOWER(name), category_type)
    WHERE user_id IS NULL;

CREATE UNIQUE INDEX uq_categories_user_name_type
    ON categories (user_id, LOWER(name), category_type)
    WHERE user_id IS NOT NULL;

CREATE TABLE transaction_classification_rules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                VARCHAR(150) NOT NULL,
    match_type          VARCHAR(30) NOT NULL
                        CHECK (match_type IN (
                            'MERCHANT_EQUALS',
                            'MERCHANT_CONTAINS',
                            'DESCRIPTION_CONTAINS',
                            'MCC_EQUALS'
                        )),
    match_value         VARCHAR(255) NOT NULL,
    category_id         UUID NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    priority            INTEGER NOT NULL DEFAULT 100 CHECK (priority >= 0),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_classification_rules_id_user UNIQUE (id, user_id)
);

CREATE INDEX idx_classification_rules_user_active_priority
    ON transaction_classification_rules (user_id, is_active, priority);

CREATE TABLE transactions (
    id                      UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bank_connection_id      UUID,
    external_transaction_id VARCHAR(255),
    category_id             UUID REFERENCES categories(id) ON DELETE SET NULL,
    classification_rule_id  UUID,
    amount                  NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    currency_code           CHAR(3) NOT NULL,
    transaction_type        VARCHAR(10) NOT NULL
                            CHECK (transaction_type IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    merchant_name           VARCHAR(255),
    mcc                     VARCHAR(4),
    description             TEXT,
    occurred_at             TIMESTAMPTZ NOT NULL,
    imported_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    transaction_source      VARCHAR(20) NOT NULL DEFAULT 'BANK'
                            CHECK (transaction_source IN ('BANK', 'MANUAL', 'IMPORT')),
    classification_method   VARCHAR(20) NOT NULL DEFAULT 'NONE'
                            CHECK (classification_method IN ('RULE', 'MANUAL', 'MCC', 'AI', 'NONE')),
    transaction_status      VARCHAR(20) NOT NULL DEFAULT 'POSTED'
                            CHECK (transaction_status IN ('PENDING', 'POSTED', 'REVERSED', 'DECLINED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_transactions PRIMARY KEY (id, occurred_at),
    CONSTRAINT uq_transactions_id_user_occurred UNIQUE (id, user_id, occurred_at),
    CONSTRAINT fk_transactions_bank_connection
        FOREIGN KEY (bank_connection_id, user_id)
        REFERENCES bank_connections (id, user_id)
        ON DELETE SET NULL (bank_connection_id),
    CONSTRAINT fk_transactions_classification_rule
        FOREIGN KEY (classification_rule_id, user_id)
        REFERENCES transaction_classification_rules (id, user_id)
        ON DELETE SET NULL (classification_rule_id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX idx_transactions_user_occurred_at
    ON transactions (user_id, occurred_at DESC);

CREATE INDEX idx_transactions_user_category_occurred_at
    ON transactions (user_id, category_id, occurred_at DESC);

CREATE INDEX idx_transactions_bank_connection
    ON transactions (bank_connection_id)
    WHERE bank_connection_id IS NOT NULL;

CREATE UNIQUE INDEX uq_transactions_external_id_timestamp
    ON transactions (bank_connection_id, external_transaction_id, occurred_at)
    WHERE bank_connection_id IS NOT NULL AND external_transaction_id IS NOT NULL;

CREATE OR REPLACE FUNCTION ensure_transaction_partition(p_occurred_at TIMESTAMPTZ)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_month_start       TIMESTAMP WITHOUT TIME ZONE;
    v_month_end         TIMESTAMP WITHOUT TIME ZONE;
    v_partition_name    TEXT;
BEGIN
    IF p_occurred_at IS NULL THEN
        RAISE EXCEPTION 'p_occurred_at cannot be NULL';
    END IF;

    v_month_start := DATE_TRUNC('month', p_occurred_at AT TIME ZONE 'UTC');
    v_month_end := v_month_start + INTERVAL '1 month';
    v_partition_name := 'transactions_' || TO_CHAR(v_month_start, 'YYYY_MM');

    EXECUTE FORMAT(
        'CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.transactions '
        'FOR VALUES FROM (%L) TO (%L)',
        v_partition_name,
        v_month_start AT TIME ZONE 'UTC',
        v_month_end AT TIME ZONE 'UTC'
    );
END;
$$;

CREATE OR REPLACE FUNCTION maintain_transaction_partitions()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_current_month DATE;
    v_month         DATE;
    v_created       INTEGER := 0;
    v_before        INTEGER;
    i               INTEGER;
BEGIN
    v_current_month := DATE_TRUNC('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE;

    FOR i IN 0..2 LOOP
        v_month := (v_current_month + MAKE_INTERVAL(months => i))::DATE;

        SELECT COUNT(*) INTO v_before
        FROM pg_inherits AS inh
        JOIN pg_class AS child ON child.oid = inh.inhrelid
        JOIN pg_class AS parent ON parent.oid = inh.inhparent
        JOIN pg_namespace AS child_ns ON child_ns.oid = child.relnamespace
        JOIN pg_namespace AS parent_ns ON parent_ns.oid = parent.relnamespace
        WHERE parent_ns.nspname = 'public'
          AND parent.relname = 'transactions'
          AND child_ns.nspname = 'public'
          AND child.relname = 'transactions_' || TO_CHAR(v_month, 'YYYY_MM');

        PERFORM public.ensure_transaction_partition(v_month::TIMESTAMP AT TIME ZONE 'UTC');

        IF v_before = 0 THEN
            v_created := v_created + 1;
        END IF;
    END LOOP;

    RETURN v_created;
END;
$$;

DO $$
DECLARE
    v_current_month DATE;
    v_month         DATE;
    i               INTEGER;
BEGIN
    v_current_month := DATE_TRUNC('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::DATE;

    FOR i IN 0..2 LOOP
        v_month := (v_current_month + MAKE_INTERVAL(months => i))::DATE;
        PERFORM public.ensure_transaction_partition(v_month::TIMESTAMP AT TIME ZONE 'UTC');
    END LOOP;
END;
$$;

CREATE TABLE transaction_category_history (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id              UUID NOT NULL,
    transaction_occurred_at     TIMESTAMPTZ NOT NULL,
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    previous_category_id        UUID REFERENCES categories(id) ON DELETE SET NULL,
    new_category_id             UUID REFERENCES categories(id) ON DELETE SET NULL,
    changed_by_user_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    changed_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    change_reason               TEXT,
    CONSTRAINT fk_category_history_transaction
        FOREIGN KEY (transaction_id, user_id, transaction_occurred_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON DELETE CASCADE
);

CREATE INDEX idx_transaction_category_history_transaction
    ON transaction_category_history (transaction_id, transaction_occurred_at, changed_at DESC);

CREATE INDEX idx_transaction_category_history_user
    ON transaction_category_history (user_id, changed_at DESC);

CREATE TABLE budgets (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_start            DATE NOT NULL,
    period_end              DATE NOT NULL,
    planned_income          NUMERIC(19, 4) CHECK (planned_income IS NULL OR planned_income >= 0),
    total_expense_limit     NUMERIC(19, 4) NOT NULL CHECK (total_expense_limit >= 0),
    currency_code           CHAR(3) NOT NULL,
    budget_status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                            CHECK (budget_status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_budgets_period CHECK (period_start < period_end),
    CONSTRAINT uq_budgets_user_period UNIQUE (user_id, period_start)
);

CREATE TABLE budget_category_allocations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    budget_id               UUID NOT NULL REFERENCES budgets(id) ON DELETE CASCADE,
    category_id             UUID NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    allocation_amount       NUMERIC(19, 4) CHECK (allocation_amount IS NULL OR allocation_amount >= 0),
    allocation_percentage   NUMERIC(5, 2) CHECK (
                                allocation_percentage IS NULL
                                OR allocation_percentage BETWEEN 0 AND 100
                            ),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_budget_category UNIQUE (budget_id, category_id),
    CONSTRAINT chk_budget_allocation_has_value
        CHECK (allocation_amount IS NOT NULL OR allocation_percentage IS NOT NULL)
);

CREATE TABLE savings_goals (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                    VARCHAR(150) NOT NULL,
    description             TEXT,
    target_amount           NUMERIC(19, 4) NOT NULL CHECK (target_amount > 0),
    currency_code           CHAR(3) NOT NULL,
    target_date             DATE,
    goal_status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (goal_status IN ('ACTIVE', 'COMPLETED', 'PAUSED', 'CANCELLED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_savings_goals_id_user UNIQUE (id, user_id)
);

CREATE INDEX idx_savings_goals_user_status ON savings_goals (user_id, goal_status);

CREATE TABLE savings_goal_contributions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    savings_goal_id             UUID NOT NULL,
    user_id                     UUID NOT NULL,
    transaction_id              UUID,
    transaction_occurred_at     TIMESTAMPTZ,
    amount                      NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    contributed_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note                        TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_contributions_goal
        FOREIGN KEY (savings_goal_id, user_id)
        REFERENCES savings_goals (id, user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_contributions_transaction
        FOREIGN KEY (transaction_id, user_id, transaction_occurred_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON DELETE SET NULL (transaction_id, transaction_occurred_at),
    CONSTRAINT chk_contribution_transaction_pair
        CHECK ((transaction_id IS NULL) = (transaction_occurred_at IS NULL))
);

CREATE INDEX idx_savings_goal_contributions_goal_date
    ON savings_goal_contributions (savings_goal_id, contributed_at DESC);

CREATE UNIQUE INDEX uq_savings_goal_contributions_transaction
    ON savings_goal_contributions (transaction_id, transaction_occurred_at)
    WHERE transaction_id IS NOT NULL;

CREATE TABLE notifications (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type           VARCHAR(50) NOT NULL,
    title                       VARCHAR(200) NOT NULL,
    message                     TEXT NOT NULL,
    payload                     JSONB NOT NULL DEFAULT '{}'::JSONB,
    related_transaction_id      UUID,
    related_transaction_at      TIMESTAMPTZ,
    read_at                     TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notifications_transaction
        FOREIGN KEY (related_transaction_id, user_id, related_transaction_at)
        REFERENCES transactions (id, user_id, occurred_at)
        ON DELETE SET NULL (related_transaction_id, related_transaction_at),
    CONSTRAINT chk_notifications_transaction_pair
        CHECK ((related_transaction_id IS NULL) = (related_transaction_at IS NULL))
);

CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);

CREATE INDEX idx_notifications_unread
    ON notifications (user_id, created_at DESC)
    WHERE read_at IS NULL;

CREATE TABLE push_subscriptions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint            TEXT NOT NULL UNIQUE,
    p256dh_key          TEXT NOT NULL,
    auth_key            TEXT NOT NULL,
    user_agent          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at        TIMESTAMPTZ,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_push_subscriptions_user_active
    ON push_subscriptions (user_id, is_active);

CREATE TABLE notification_deliveries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id         UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    push_subscription_id    UUID REFERENCES push_subscriptions(id) ON DELETE SET NULL,
    delivery_status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED')),
    attempt_number          INTEGER NOT NULL DEFAULT 1 CHECK (attempt_number > 0),
    attempted_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_status_code    INTEGER,
    error_message           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_delivery_attempt
        UNIQUE (notification_id, push_subscription_id, attempt_number)
);

CREATE INDEX idx_notification_deliveries_notification
    ON notification_deliveries (notification_id, attempted_at DESC);

CREATE TABLE audit_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type          VARCHAR(100) NOT NULL,
    entity_type         VARCHAR(100) NOT NULL,
    entity_id           UUID,
    event_details       JSONB NOT NULL DEFAULT '{}'::JSONB,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address          INET,
    user_agent          TEXT
);

CREATE INDEX idx_audit_events_actor_time
    ON audit_events (actor_user_id, occurred_at DESC);

CREATE INDEX idx_audit_events_entity
    ON audit_events (entity_type, entity_id, occurred_at DESC);
