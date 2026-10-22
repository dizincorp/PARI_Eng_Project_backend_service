package pl.pollub.pari.auth.security;

import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the id of the authenticated caller into a controller method. The value is {@code null} on endpoints that are
 * open to anonymous callers when nobody is logged in; on protected endpoints it is never null.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(hidden = true)
@AuthenticationPrincipal(expression = "#this instanceof T(pl.pollub.pari.auth.security.AuthUser) ? #this.id() : null")
public @interface CurrentUserId {
}
