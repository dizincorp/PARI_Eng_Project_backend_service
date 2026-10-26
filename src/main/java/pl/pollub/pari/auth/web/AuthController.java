package pl.pollub.pari.auth.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.auth.service.AuthService;
import pl.pollub.pari.user.domain.Role;

import java.util.UUID;

/** Registration, login and the profile of the logged-in user. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** {@code role} is DRIVER or OWNER. The password must have 8 to 72 characters (BCrypt limit). */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a phone number") String phoneNumber,
            @NotNull Role role) {
    }

    /** Updates the profile: a {@code phoneNumber} or {@code avatarUrl} that is left out stays unchanged, an empty one removes it. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a phone number") String phoneNumber,
            @Size(max = 500) @Pattern(regexp = "^(https?://\\S+)?$", message = "must be an http(s) URL") String avatarUrl) {
    }

    /** The new password must have 8 to 72 characters (BCrypt limit). */
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record MessageResponse(String message) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.UserView register(@Valid @RequestBody RegisterRequest r) {
        return auth.register(r.email(), r.password(), r.firstName(), r.lastName(), r.phoneNumber(), r.role());
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@Valid @RequestBody LoginRequest r) {
        return auth.login(r.email(), r.password());
    }

    /** 400 {@code INVALID_CURRENT_PASSWORD} when the current password is wrong; 401 without a token. */
    @PostMapping("/change-password")
    public MessageResponse changePassword(@CurrentUserId UUID userId, @Valid @RequestBody ChangePasswordRequest r) {
        auth.changePassword(userId, r.currentPassword(), r.newPassword());
        return new MessageResponse("Password changed");
    }

    @PutMapping("/profile")
    public AuthService.UserView updateProfile(@CurrentUserId UUID userId, @Valid @RequestBody UpdateProfileRequest r) {
        return auth.updateProfile(userId, r.firstName(), r.lastName(), r.phoneNumber(), r.avatarUrl());
    }

    @GetMapping("/me")
    public AuthService.UserView me(@CurrentUserId UUID userId) {
        return auth.me(userId);
    }
}
