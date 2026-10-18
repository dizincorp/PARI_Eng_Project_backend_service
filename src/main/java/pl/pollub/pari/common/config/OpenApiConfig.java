package pl.pollub.pari.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for Swagger UI. The {@code X-User-Id} header is modelled as an API key so it can be filled once via
 * "Authorize". TEMPORARY: replace with a bearer JWT scheme when Spring Security is added.
 */
@Configuration
public class OpenApiConfig {

    private static final String USER_ID_SCHEME = "X-User-Id";

    @Bean
    OpenAPI pariOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PARI backend API")
                        .version("0.0.1")
                        .description("Parking space rental portal. There is no real authentication yet: "
                                + "the caller is identified by the temporary X-User-Id header (a user UUID). "
                                + "Payments use a mock gateway."))
                .components(new Components().addSecuritySchemes(USER_ID_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-User-Id")
                        .description("Id of the acting user (temporary, until JWT is added)")))
                .addSecurityItem(new SecurityRequirement().addList(USER_ID_SCHEME));
    }
}
