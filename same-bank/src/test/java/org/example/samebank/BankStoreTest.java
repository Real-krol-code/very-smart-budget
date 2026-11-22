package org.example.samebank;

import org.example.samebank.BankModels.BankTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankStoreTest {
    private final BankStore store = new BankStore();
    private final YearMonth month = YearMonth.now(ZoneOffset.UTC);

    @Test
    void paginatesAllRecordsWithoutDuplicates() {
        Set<String> ids = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = store.page("ext-user-1", month, cursor, 50);
            pages++;
            page.items().forEach(t -> assertTrue(ids.add(t.id()), "duplicate id: " + t.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(120, ids.size());
        assertEquals(3, pages);
    }

    @Test
    void emptyUserReturnsEmptyPage() {
        var page = store.page("ext-user-empty", month, null, 50);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void badCursorRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> store.page("ext-user-1", month, "not-a-cursor", 50));
    }

    @Test
    void upsertReplacesDuplicate() {
        var before = store.page("ext-user-1", month, null, 200).items();
        BankTransaction t = before.get(0);
        store.upsert("ext-user-1", new BankTransaction(t.id(), new BigDecimal("999.99"), t.currency(),
                t.type(), t.merchantName(), t.mcc(), t.description(), t.occurredAt(), "REVERSED"));
        var after = store.page("ext-user-1", month, null, 200).items();
        assertEquals(120, after.size());
        var changed = after.stream().filter(x -> x.id().equals(t.id())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("999.99"), changed.amount());
        assertEquals("REVERSED", changed.status());
    }
}
