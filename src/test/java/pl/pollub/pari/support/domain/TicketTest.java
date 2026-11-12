package pl.pollub.pari.support.domain;

import org.junit.jupiter.api.Test;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private static User user(Role role) {
        return new User(role.name().toLowerCase() + "@example.com", "hash", "A", "B", null, Set.of(role));
    }

    private static Report ticket(TicketType type, TicketSource source) {
        return Report.ticket(null, source == TicketSource.SYSTEM ? null : user(Role.OWNER), user(Role.DRIVER), type, source, "x");
    }

    @Test
    void vehicleNotLeftAndCannotLeaveAreHighPriorityOtherIsNormal() {
        assertThat(ticket(TicketType.VEHICLE_NOT_LEFT, TicketSource.OWNER).getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(ticket(TicketType.CANNOT_LEAVE, TicketSource.DRIVER).getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(ticket(TicketType.OTHER, TicketSource.DRIVER).getPriority()).isEqualTo(TicketPriority.NORMAL);
    }

    @Test
    void aSystemTicketHasNoReporter() {
        Report t = ticket(TicketType.VEHICLE_NOT_LEFT, TicketSource.SYSTEM);

        assertThat(t.getReporter()).isNull();
        assertThat(t.getSource()).isEqualTo(TicketSource.SYSTEM);
        assertThat(t.isOpen()).isTrue();
    }

    @Test
    void supportCanClaimAndHandOverAnOpenTicketButNotAClosedOne() {
        Report t = ticket(TicketType.OTHER, TicketSource.DRIVER);
        User first = user(Role.SUPPORT);
        User second = user(Role.SUPPORT);

        t.claim(first);
        assertThat(t.getAssignedTo()).isSameAs(first);
        assertThat(t.getStatus()).isEqualTo(ReportStatus.IN_PROGRESS);

        t.reassign(second);
        assertThat(t.getAssignedTo()).isSameAs(second);

        t.resolve("done");
        t.close(Instant.parse("2030-01-01T00:00:00Z"));
        assertThatThrownBy(() -> t.reassign(first)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void evacuationGoesRequestedThenInProgressThenDone() {
        Report t = ticket(TicketType.VEHICLE_NOT_LEFT, TicketSource.OWNER);

        t.requestEvacuation("Public car park, Lublin", Money.pln(15000));
        assertThat(t.getEvacuationStatus()).isEqualTo(EvacuationStatus.REQUESTED);

        assertThatThrownBy(() -> t.advanceEvacuation(EvacuationStatus.DONE)).isInstanceOf(IllegalStateTransitionException.class);
        t.advanceEvacuation(EvacuationStatus.IN_PROGRESS);
        t.advanceEvacuation(EvacuationStatus.DONE);
        assertThat(t.getEvacuationStatus()).isEqualTo(EvacuationStatus.DONE);
        assertThatThrownBy(() -> t.advanceEvacuation(EvacuationStatus.IN_PROGRESS)).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void anEvacuationCanBeStartedOnlyOnce() {
        Report t = ticket(TicketType.VEHICLE_NOT_LEFT, TicketSource.OWNER);
        t.requestEvacuation("Impound lot", Money.pln(0));

        assertThatThrownBy(() -> t.requestEvacuation("Other", Money.pln(0))).isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void theEvacuationFeeCanBeAdjustedOnlyWhenThereIsAnEvacuation() {
        Report t = ticket(TicketType.VEHICLE_NOT_LEFT, TicketSource.OWNER);
        assertThatThrownBy(() -> t.adjustEvacuationFee(Money.ZERO)).isInstanceOf(IllegalStateTransitionException.class);

        t.requestEvacuation("Impound lot", Money.pln(10000));
        t.adjustEvacuationFee(Money.ZERO);

        assertThat(t.getEvacuationFee()).isEqualTo(Money.ZERO);
    }
}
