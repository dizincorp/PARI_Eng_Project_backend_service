package pl.pollub.pari.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.auth.security.SecurityConfig;
import pl.pollub.pari.auth.service.AuthService;
import pl.pollub.pari.auth.web.AuthController;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.reservation.service.PricingService;
import pl.pollub.pari.reservation.service.ReservationService;
import pl.pollub.pari.reservation.web.ReservationController;
import pl.pollub.pari.user.domain.Role;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Which endpoints need a token, how tokens are checked, and the shape of the auth endpoints. Services are mocked. */
@WebMvcTest({AuthController.class, ReservationController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class, SecurityRulesTest.Time.class})
class SecurityRulesTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2029-12-01T00:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder encoder;
    @MockitoBean
    AuthService auth;
    @MockitoBean
    ReservationService reservations;

    @Test
    void aProtectedEndpointWithoutATokenReturns401InTheApiErrorFormat() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.statusCode").value(401));
    }

    @Test
    void aValidTokenIdentifiesTheCaller() throws Exception {
        when(auth.me(USER)).thenReturn(new AuthService.UserView(USER, "jan@example.com", "Jan", "K", Role.DRIVER, 0));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(USER, 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jan@example.com"));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(USER, -3600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = token(USER, 3600);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token.substring(0, token.length() - 4) + "AAAA"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reservationEndpointsUseTheUserOfTheToken() throws Exception {
        when(reservations.listForDriver(eq(USER), any(Integer.class), any(Integer.class))).thenReturn(List.of());

        mvc.perform(get("/api/reservations").header("Authorization", "Bearer " + token(USER, 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
    }

    @Test
    void registerAndLoginAreOpen() throws Exception {
        when(auth.register(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuthService.UserView(USER, "jan@example.com", "Jan", "K", Role.DRIVER, 0));
        when(auth.login(any(), any())).thenReturn(new AuthService.AuthResponse("t", "Bearer", 86400,
                new AuthService.UserView(USER, "jan@example.com", "Jan", "K", Role.DRIVER, 0)));

        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                        {"email":"jan@example.com","password":"SecurePassword123!","firstName":"Jan","lastName":"K","role":"DRIVER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DRIVER"));
        mvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"email":"jan@example.com","password":"SecurePassword123!"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(86400));
    }

    @Test
    void registrationValidatesTheBody() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                        {"email":"not-an-email","password":"short","firstName":"","lastName":"K","role":"DRIVER"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void swaggerAndTheApiDocsAreOpen() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    /** Token that expires the given number of seconds from the real current time (the decoder uses the system clock). */
    private String token(UUID subject, long expiresInSeconds) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(subject.toString()).issuedAt(now.minusSeconds(7200))
                .expiresAt(now.plusSeconds(expiresInSeconds)).claim("roles", List.of("DRIVER")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @TestConfiguration
    static class Time {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
