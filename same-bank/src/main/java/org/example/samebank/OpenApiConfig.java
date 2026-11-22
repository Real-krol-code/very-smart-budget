package org.example.samebank;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {
    @Bean
    OpenAPI sameBankOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("serviceToken",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Service-Token")))
                .addSecurityItem(new SecurityRequirement().addList("serviceToken"));
    }
}
