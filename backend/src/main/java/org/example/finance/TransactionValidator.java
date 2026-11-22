package org.example.finance;

import org.example.finance.BankDtos.BankTransactionDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class TransactionValidator {
    private static final Set<String> TYPES = Set.of("INCOME", "EXPENSE", "TRANSFER");
    private static final Set<String> STATUSES = Set.of("PENDING", "POSTED", "REVERSED", "DECLINED");

    public List<String> validate(BankTransactionDto t, YearMonth month) {
        List<String> errors = new ArrayList<>();
        if (t == null) return List.of("record is null");
        if (t.id() == null || t.id().isBlank() || t.id().length() > 255)
            errors.add("id: required, <= 255 chars");
        BigDecimal a = t.amount();
        if (a == null || a.signum() < 0) errors.add("amount: required, >= 0");
        else {
            var s = a.stripTrailingZeros();
            if (s.scale() > 4 || s.precision() - s.scale() > 15)
                errors.add("amount: must fit NUMERIC(19,4)");
        }
        if (t.currency() == null || !t.currency().matches("[A-Z]{3}"))
            errors.add("currency: ISO-4217 alpha-3");
        if (!TYPES.contains(t.type())) errors.add("type: INCOME/EXPENSE/TRANSFER");
        if (!STATUSES.contains(t.status())) errors.add("status: PENDING/POSTED/REVERSED/DECLINED");
        if (t.occurredAt() == null) errors.add("occurredAt: required");
        else if (!YearMonth.from(t.occurredAt().atZoneSameInstant(ZoneOffset.UTC)).equals(month))
            errors.add("occurredAt: outside requested month");
        if (t.mcc() != null && !t.mcc().matches("\\d{4}")) errors.add("mcc: exactly 4 digits");
        if (t.merchantName() != null && t.merchantName().length() > 255)
            errors.add("merchantName: <= 255 chars");
        return errors;
    }
}
