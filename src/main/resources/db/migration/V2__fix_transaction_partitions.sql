CREATE TABLE public.transactions_default
    PARTITION OF public.transactions DEFAULT;


CREATE INDEX idx_transactions_default_occurred_at
    ON public.transactions_default (occurred_at);

CREATE OR REPLACE FUNCTION public.ensure_transaction_partition(
    p_occurred_at TIMESTAMPTZ
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
v_month_start TIMESTAMP WITHOUT TIME ZONE;
    v_month_end TIMESTAMP WITHOUT TIME ZONE;
    v_partition_name TEXT;
    v_partition_exists BOOLEAN;
    v_default_has_rows BOOLEAN;
BEGIN
    IF p_occurred_at IS NULL THEN
        RAISE EXCEPTION 'Дата транзакции для создания партиции не может быть NULL';
END IF;

    v_month_start := DATE_TRUNC(
        'month',
        p_occurred_at AT TIME ZONE 'UTC'
    );

    v_month_end := v_month_start + INTERVAL '1 month';

    v_partition_name :=
        'transactions_' || TO_CHAR(v_month_start, 'YYYY_MM');


SELECT EXISTS (
    SELECT 1
    FROM pg_inherits AS inh
             JOIN pg_class AS child
                  ON child.oid = inh.inhrelid
             JOIN pg_namespace AS child_ns
                  ON child_ns.oid = child.relnamespace
    WHERE inh.inhparent = 'public.transactions'::regclass
        AND child_ns.nspname = 'public'
        AND child.relname = v_partition_name
)
INTO v_partition_exists;

IF v_partition_exists THEN
        RETURN;
END IF;

    LOCK TABLE public.transactions IN ACCESS EXCLUSIVE MODE;
    LOCK TABLE public.transactions_default IN ACCESS EXCLUSIVE MODE;

SELECT EXISTS (
    SELECT 1
    FROM pg_inherits AS inh
             JOIN pg_class AS child
                  ON child.oid = inh.inhrelid
             JOIN pg_namespace AS child_ns
                  ON child_ns.oid = child.relnamespace
    WHERE inh.inhparent = 'public.transactions'::regclass
        AND child_ns.nspname = 'public'
        AND child.relname = v_partition_name
)
INTO v_partition_exists;

IF v_partition_exists THEN
        RETURN;
END IF;


SELECT EXISTS (
    SELECT 1
    FROM public.transactions_default
    WHERE occurred_at >= (v_month_start AT TIME ZONE 'UTC')
      AND occurred_at < (v_month_end AT TIME ZONE 'UTC')
)
INTO v_default_has_rows;

IF v_default_has_rows THEN
        RAISE NOTICE
            'Партиция % не создана: в резервной партиции уже есть транзакции за этот месяц',
            v_partition_name;

        RETURN;
END IF;


EXECUTE FORMAT(
        'CREATE TABLE public.%I PARTITION OF public.transactions
         FOR VALUES FROM (%L) TO (%L)',
        v_partition_name,
        v_month_start AT TIME ZONE 'UTC',
        v_month_end AT TIME ZONE 'UTC'
        );

RAISE NOTICE 'Создана месячная партиция %', v_partition_name;
END;
$$;



CREATE OR REPLACE FUNCTION public.maintain_transaction_partitions()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
v_current_month DATE;
    v_month DATE;
    v_partition_name TEXT;
    v_partition_exists_before BOOLEAN;
    v_partition_exists_after BOOLEAN;
    v_created INTEGER := 0;
    i INTEGER;
BEGIN

    v_current_month :=
        DATE_TRUNC(
            'month',
            CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
        )::DATE;

FOR i IN 0..2 LOOP

        v_month :=
            (v_current_month + MAKE_INTERVAL(months => i))::DATE;

        v_partition_name :=
            'transactions_' || TO_CHAR(v_month, 'YYYY_MM');


SELECT EXISTS (
    SELECT 1
    FROM pg_inherits AS inh
             JOIN pg_class AS child
                  ON child.oid = inh.inhrelid
             JOIN pg_namespace AS child_ns
                  ON child_ns.oid = child.relnamespace
    WHERE inh.inhparent = 'public.transactions'::regclass
        AND child_ns.nspname = 'public'
        AND child.relname = v_partition_name
)
INTO v_partition_exists_before;


PERFORM public.ensure_transaction_partition(
            v_month::TIMESTAMP AT TIME ZONE 'UTC'
        );


SELECT EXISTS (
    SELECT 1
    FROM pg_inherits AS inh
             JOIN pg_class AS child
                  ON child.oid = inh.inhrelid
             JOIN pg_namespace AS child_ns
                  ON child_ns.oid = child.relnamespace
    WHERE inh.inhparent = 'public.transactions'::regclass
        AND child_ns.nspname = 'public'
        AND child.relname = v_partition_name
)
INTO v_partition_exists_after;

IF NOT v_partition_exists_before
           AND v_partition_exists_after THEN
            v_created := v_created + 1;
END IF;

END LOOP;

    RAISE NOTICE
        'Обслуживание партиций завершено. Создано новых партиций: %',
        v_created;

RETURN v_created;
END;
$$;


SELECT public.maintain_transaction_partitions();
