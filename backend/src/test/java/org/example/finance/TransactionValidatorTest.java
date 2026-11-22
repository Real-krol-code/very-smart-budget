package org.example.finance;

import org.example.finance.BankDtos.BankTransactionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionValidatorTest {
    private final TransactionValidator validator = new TransactionValidator();
    private final YearMonth month = YearMonth.of(2026, 11);
    private final OffsetDateTime ts = OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC);

    private BankTransactionDto dto(BigDecimal amount, String currency, String type,
                                   OffsetDateTime occurredAt, String mcc) {
        return new BankTransactionDto("txn-1", amount, currency, type, "Shop", mcc, "d", occurredAt, "POSTED");
    }

    @Test
    void validRecordHasNoErrors() {
        assertTrue(validator.validate(dto(new BigDecimal("10.50"), "RUB", "EXPENSE", ts, "5411"), month).isEmpty());
    }

    @Test
    void rejectsBrokenFields() {
        assertFalse(validator.validate(dto(new BigDecimal("-1"), "RUB", "EXPENSE", ts, "5411"), month).isEmpty());
        assertFalse(validator.validate(dto(BigDecimal.TEN, "EURO", "EXPENSE", ts, "5411"), month).isEmpty());
        assertFalse(validator.validate(dto(BigDecimal.TEN, "RUB", "REFUND", ts, "5411"), month).isEmpty());
        assertFalse(validator.validate(dto(BigDecimal.TEN, "RUB", "EXPENSE", ts, "12A4"), month).isEmpty());
        assertFalse(validator.validate(dto(BigDecimal.TEN, "RUB", "EXPENSE", null, "5411"), month).isEmpty());
        assertFalse(validator.validate(dto(new BigDecimal("0.00001"), "RUB", "EXPENSE", ts, "5411"), month).isEmpty());
    }

    @Test
    void rejectsRecordOutsideRequestedMonth() {
        assertFalse(validator.validate(dto(BigDecimal.TEN, "RUB", "EXPENSE", ts.minusMonths(1), "5411"), month).isEmpty());
    }
}
