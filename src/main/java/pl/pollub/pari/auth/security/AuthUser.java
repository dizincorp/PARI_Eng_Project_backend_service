package pl.pollub.pari.auth.security;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller, taken from a verified JWT (or, in development only, from the X-User-Id header). */
public record AuthUser(UUID id, Set<String> roles) {
}
