package org.example.samebank;

import org.example.samebank.BankModels.BankTransaction;
import org.example.samebank.BankModels.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@RestController
class BankController {
    private final BankStore store;
    BankController(BankStore store) { this.store = store; }

    @GetMapping("/v1/users/{externalUserId}/transactions")
    Page transactions(@PathVariable String externalUserId,
                      @RequestParam(required = false) String month,
                      @RequestParam(required = false) String cursor,
                      @RequestParam(defaultValue = "50") int limit) {
        if (month == null || month.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month is required (YYYY-MM)");
        YearMonth ym;
        try { ym = YearMonth.parse(month); }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        if (limit < 1 || limit > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..200");
        if (!store.hasUser(externalUserId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown externalUserId");
        try { return store.page(externalUserId, ym, cursor, limit); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
        }
    }

    // тестовый стенд: изменить данные в банке (сценарий "изменение данных у дубля")
    @PostMapping("/test/users/{externalUserId}/transactions")
    void upsert(@PathVariable String externalUserId, @RequestBody BankTransaction t) {
        store.upsert(externalUserId, t);
    }
}
