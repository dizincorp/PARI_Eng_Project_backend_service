package pl.pollub.pari.devdata;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the scenario seed may run (PARI-94). It refuses unless the application runs under the {@code local} or
 * {@code test} profile, never under a production-like profile, and only against a database on this machine or one of the
 * service names of docker-compose.yml. A refusal throws, so the application does not start: the seed never silently
 * does nothing in a place where somebody expected it to be off, and it never writes to a database it should not touch.
 */
public final class SeedGuard {

    private static final Set<String> ALLOWED_PROFILES = Set.of("local", "test");
    private static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "staging", "live");
    private static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "postgres", "postgres-seeded");

    private SeedGuard() {
    }

    public static void check(Environment env, String jdbcUrl) {
        check(List.of(env.getActiveProfiles()), jdbcUrl);
    }

    public static void check(List<String> activeProfiles, String jdbcUrl) {
        List<String> profiles = activeProfiles.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        if (profiles.stream().anyMatch(FORBIDDEN_PROFILES::contains)) {
            throw refuse("a production-like profile is active (" + profiles + ")");
        }
        if (profiles.stream().noneMatch(ALLOWED_PROFILES::contains)) {
            throw refuse("the 'local' or 'test' profile must be active (active: " + profiles + ")");
        }
        String host = hostOf(jdbcUrl);
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw refuse("the database host is not local (" + host + ")");
        }
    }

    /** The host of {@code jdbc:postgresql://host:port/db}, or null when the URL cannot be read. */
    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static IllegalStateException refuse(String why) {
        return new IllegalStateException("The scenario seed refuses to run: " + why
                + ". It is for a local or test database only; set pari.seed.enabled=false to switch it off.");
    }
}
