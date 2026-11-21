package org.example.finance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "same-bank")
public record SameBankProperties(String baseUrl, String serviceToken, int pageSize) {}
