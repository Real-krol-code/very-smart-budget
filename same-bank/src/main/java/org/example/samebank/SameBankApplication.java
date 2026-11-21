package org.example.samebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SameBankProps.class)
public class SameBankApplication {
    public static void main(String[] args) { SpringApplication.run(SameBankApplication.class, args); }
}

@ConfigurationProperties(prefix = "same-bank")
record SameBankProps(String serviceToken) {}
