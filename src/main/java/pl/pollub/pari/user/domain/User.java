package pl.pollub.pari.user.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.security.EncryptedStringConverter;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
public class User extends BaseEntity {

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Setter
    @Column(nullable = false)
    private String firstName;

    @Setter
    @Column(nullable = false)
    private String lastName;

    /** HMAC of the normalised phone number, unique; lets the application detect a number used twice (see PhoneHasher). */
    @Column(name = "phone_hash")
    private String phoneHash;

    @Setter
    private String avatarUrl;

    /** Encrypted at rest (AES-256-GCM, NF04). */
    @Setter
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "phone_enc")
    private String phone;

    private boolean emailVerified;

    private boolean phoneVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role activeRole;

    private int loyaltyPoints;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    protected User() {
    }

    public User(String email, String passwordHash, String firstName, String lastName, String phone, Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("a user needs at least one role");
        }
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.roles = EnumSet.copyOf(roles);
        this.activeRole = roles.contains(Role.DRIVER) ? Role.DRIVER : this.roles.iterator().next();
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public void grantRole(Role role) {
        roles.add(role);
    }

    public void revokeRole(Role role) {
        roles.remove(role);
        if (roles.isEmpty()) {
            throw new IllegalStateTransitionException("a user must keep at least one role");
        }
        if (activeRole == role) {
            activeRole = roles.iterator().next();
        }
    }

    /** Switches the role shown in the UI; allowed only for roles the user holds. */
    public void switchRole(Role role) {
        if (!hasRole(role)) {
            throw new IllegalStateTransitionException("user does not hold the role " + role);
        }
        this.activeRole = role;
    }

    /** Sets or clears the phone number together with its fingerprint; a changed number is no longer verified. */
    public void changePhone(String phone, String hash) {
        if (phone == null ? this.phone != null : !phone.equals(this.phone)) {
            this.phoneVerified = false;
        }
        this.phone = phone;
        this.phoneHash = hash;
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    public void confirmEmail() {
        this.emailVerified = true;
    }

    public void confirmPhone() {
        this.phoneVerified = true;
    }

    public boolean isBlocked() {
        return status == UserStatus.BLOCKED;
    }

    public void block() {
        this.status = UserStatus.BLOCKED;
    }

    public void unblock() {
        this.status = UserStatus.ACTIVE;
    }

    public void addLoyaltyPoints(int points) {
        if (loyaltyPoints + points < 0) {
            throw new IllegalArgumentException("loyalty points cannot become negative");
        }
        this.loyaltyPoints += points;
    }
}
