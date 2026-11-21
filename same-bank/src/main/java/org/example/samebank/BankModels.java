package org.example.samebank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class BankModels {
    private BankModels() {}

    public record BankTransaction(String id, BigDecimal amount, String currency, String type,
                                  String merchantName, String mcc, String description,
                                  OffsetDateTime occurredAt, String status) {}

    public record Page(List<BankTransaction> items, String nextCursor) {}
}
