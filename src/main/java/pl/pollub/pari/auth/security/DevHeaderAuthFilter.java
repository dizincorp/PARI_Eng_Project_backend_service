package pl.pollub.pari.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * DEVELOPMENT AND TESTS ONLY (enabled by {@code pari.security.jwt.dev-header-auth}): authenticates a request that carries
 * an X-User-Id header and no Authorization header. Anyone can send any id, so it must never be enabled in production.
 */
class DevHeaderAuthFilter extends OncePerRequestFilter {

    static final String USER_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(USER_HEADER);
        if (header != null && request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(new AuthUser(UUID.fromString(header.trim()), Set.of()), null, Set.of()));
            } catch (IllegalArgumentException e) {
                // not a UUID: stay anonymous, protected endpoints answer 401
            }
        }
        chain.doFilter(request, response);
    }
}
