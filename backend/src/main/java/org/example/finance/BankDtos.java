package org.example.finance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class BankDtos {
    private BankDtos() {}

    public record BankTransactionDto(String id, BigDecimal amount, String currency, String type,
                                     String merchantName, String mcc, String description,
                                     OffsetDateTime occurredAt, String status) {}

    public record BankPage(List<BankTransactionDto> items, String nextCursor) {}
}
