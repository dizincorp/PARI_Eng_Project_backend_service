package pl.pollub.pari.support.service;

import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.util.List;
import java.util.UUID;

/** A support agent or administrator who can be assigned a ticket: only what the assignment list needs (PARI-83). */
public record StaffView(UUID id, String displayName, List<Role> roles) {

    public static StaffView of(User u) {
        return new StaffView(u.getId(), u.getFirstName() + " " + u.getLastName(),
                u.getRoles().stream().filter(r -> r == Role.SUPPORT || r == Role.ADMIN).sorted().toList());
    }
}
