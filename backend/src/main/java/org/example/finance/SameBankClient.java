package org.example.finance;

import org.example.finance.BankDtos.BankPage;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.YearMonth;

@Component
public class SameBankClient {
    private final RestClient rest;
    private final int pageSize;

    public SameBankClient(SameBankProperties p) {
        this.rest = RestClient.builder()
                .baseUrl(p.baseUrl())
                .defaultHeader("X-Service-Token", p.serviceToken())
                .build();
        this.pageSize = p.pageSize();
    }

    public BankPage fetchPage(String externalUserId, YearMonth month, String cursor) {
        try {
            return rest.get().uri(b -> {
                var u = b.path("/v1/users/{u}/transactions")
                        .queryParam("month", month.toString())
                        .queryParam("limit", pageSize);
                if (cursor != null) u.queryParam("cursor", cursor);
                return u.build(externalUserId);
            }).retrieve().body(BankPage.class);
        } catch (RestClientResponseException e) {
            throw new BankApiException("HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new BankApiException("network error: " + e.getMessage());
        }
    }

    public static class BankApiException extends RuntimeException {
        public BankApiException(String message) { super(message); }
    }
}
