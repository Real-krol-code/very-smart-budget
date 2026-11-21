package org.example.samebank;

import org.example.samebank.BankModels.BankTransaction;
import org.example.samebank.BankModels.Page;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BankStore {
    private final Map<String, List<BankTransaction>> byUser = new ConcurrentHashMap<>();

    public BankStore() { seedDemoData(); }

    public boolean hasUser(String userId) { return byUser.containsKey(userId); }

    public void upsert(String userId, BankTransaction t) {
        byUser.compute(userId, (k, list) -> {
            var copy = list == null ? new ArrayList<BankTransaction>() : new ArrayList<>(list);
            copy.removeIf(x -> x.id().equals(t.id()));
            copy.add(t);
            return copy;
        });
    }

    public Page page(String userId, YearMonth month, String cursor, int limit) {
        List<BankTransaction> all = byUser.getOrDefault(userId, List.of()).stream()
                .filter(t -> t.occurredAt() != null && YearMonth.from(
                        t.occurredAt().atZoneSameInstant(ZoneOffset.UTC)).equals(month))
                .sorted(Comparator.comparing((BankTransaction t) -> t.occurredAt().toInstant())
                        .thenComparing(BankTransaction::id))
                .toList();

        int from = 0;
        if (cursor != null) {
            String[] parts = decode(cursor).split("\\|", 2);
            Instant cTs; String cId;
            try { cTs = OffsetDateTime.parse(parts[0]).toInstant(); cId = parts[1]; }
            catch (RuntimeException e) { throw new IllegalArgumentException("bad cursor"); }
            while (from < all.size()) {
                BankTransaction t = all.get(from);
                int cmp = t.occurredAt().toInstant().compareTo(cTs);
                if (cmp > 0 || (cmp == 0 && t.id().compareTo(cId) > 0)) break;
                from++;
            }
        }
        int to = Math.min(from + limit, all.size());
        List<BankTransaction> items = all.subList(from, to);
        String next = to < all.size() ? encode(items.get(items.size() - 1)) : null;
        return new Page(items, next);
    }

    private String encode(BankTransaction t) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (t.occurredAt() + "|" + t.id()).getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String cursor) {
        try { return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("bad cursor"); }
    }

    public void seedDemoData() {
        byUser.put("ext-user-empty", new ArrayList<>());
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant base = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String[] merchants = {"Magnit", "Lukoil", "Yandex Taxi", "Ozon"};
        String[] mccs = {"5411", "5541", "4121", "5999"};

        for (int i = 0; i < 120; i++) {   // 120 записей -> 3 страницы по 50
            int m = i % 4;
            upsert("ext-user-1", new BankTransaction(
                    "txn-%03d".formatted(i), BigDecimal.valueOf(100 + i * 7L, 2), "RUB",
                    i % 3 == 0 ? "INCOME" : "EXPENSE", merchants[m], mccs[m],
                    "demo payment " + i,
                    OffsetDateTime.ofInstant(base.plus(Duration.ofMinutes(300L * i)), ZoneOffset.UTC),
                    "POSTED"));
        }
        for (int i = 0; i < 3; i++) {
            upsert("ext-user-invalid", new BankTransaction("ok-" + i, BigDecimal.TEN, "RUB",
                    "EXPENSE", "Shop", "5411", "valid",
                    OffsetDateTime.ofInstant(base.plus(Duration.ofHours(i)), ZoneOffset.UTC), "POSTED"));
        }
        OffsetDateTime ts = OffsetDateTime.ofInstant(base.plus(Duration.ofHours(10)), ZoneOffset.UTC);
        upsert("ext-user-invalid", new BankTransaction("bad-amount", BigDecimal.valueOf(-10), "RUB", "EXPENSE", "Shop", "5411", null, ts, "POSTED"));
        upsert("ext-user-invalid", new BankTransaction("bad-currency", BigDecimal.TEN, "EURO", "EXPENSE", "Shop", "5411", null, ts, "POSTED"));
        upsert("ext-user-invalid", new BankTransaction("bad-type", BigDecimal.TEN, "RUB", "REFUND", "Shop", "5411", null, ts, "POSTED"));
        upsert("ext-user-invalid", new BankTransaction("bad-mcc", BigDecimal.TEN, "RUB", "EXPENSE", "Shop", "12A4", null, ts, "POSTED"));
    }
}
