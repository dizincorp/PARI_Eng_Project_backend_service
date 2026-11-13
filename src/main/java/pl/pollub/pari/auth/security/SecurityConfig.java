package pl.pollub.pari.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stateless JWT security (NF03, NF06). Everything under /api needs a Bearer token except registration, login, the
 * public search and read endpoints of parking spaces, and the price quote. Errors use the same body as the rest of the API.
 */
@Configuration
@Import(JwtConfig.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtProperties properties, Clock clock) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, e) ->
                writeError(response, clock, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        AccessDeniedHandler denied = (request, response, e) ->
                writeError(response, clock, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");

        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/spots/mine").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/spots/events").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/spots", "/api/spots/{id}", "/api/spots/{id}/reviews",
                                "/api/spots/{id}/availability-rules", "/api/spots/{id}/photos").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/reservations/quote").permitAll()
                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtToAuthentication()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));

        if (properties.devHeaderAuth()) {
            http.addFilterBefore(new DevHeaderAuthFilter(), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    private static Converter<Jwt, AbstractAuthenticationToken> jwtToAuthentication() {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList(JwtConfig.ROLES_CLAIM);
            Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(roles);
            Collection<GrantedAuthority> authorities = roleSet.stream()
                    .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return UsernamePasswordAuthenticationToken.authenticated(
                    new AuthUser(UUID.fromString(jwt.getSubject()), roleSet), jwt, authorities);
        };
    }

    private static void writeError(HttpServletResponse response, Clock clock, HttpStatus status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"errorCode\":\"%s\",\"message\":\"%s\",\"statusCode\":%d,\"timestamp\":\"%s\"}"
                .formatted(code, message, status.value(), clock.instant()));
    }
}
