package pl.pollub.pari.payment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;
import pl.pollub.pari.payment.domain.Withdrawal;
import pl.pollub.pari.payment.repository.PayoutRepository;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({OwnerPayoutService.class, PayoutService.class})
class OwnerPayoutServiceTest extends ServiceTestBase {

    private static final String IBAN = "PL61109010140000071219812874";

    @Autowired
    OwnerPayoutService service;
    @Autowired
    PayoutService release;
    @Autowired
    PayoutRepository payouts;

    User owner;
    ParkingSpot spot;
    User driver;
    Vehicle vehicle;
    int slot;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2, 22.5, 1000);
        em.flush();
    }

    @Test
    void balanceSplitsPendingEscrowFromReleasedMoney() {
        payout(PayoutStatus.PAID, 1000, 100);
        payout(PayoutStatus.SCHEDULED, 500, 50);
        payout(PayoutStatus.HELD, 300, 30);
        payout(PayoutStatus.CANCELLED, 9000, 900);

        OwnerPayoutService.Balance balance = service.balance(owner.getId());

        assertThat(balance.availableBalance()).isEqualTo(Money.pln(900));
        assertThat(balance.pendingEscrow()).isEqualTo(Money.pln(720));
        assertThat(balance.totalEarned()).isEqualTo(Money.pln(900));
        assertThat(balance.platformFeesPaid()).isEqualTo(Money.pln(100));
    }

    @Test
    void balanceIsZeroWithoutPayouts() {
        OwnerPayoutService.Balance balance = service.balance(owner.getId());

        assertThat(balance.availableBalance()).isEqualTo(Money.ZERO);
        assertThat(balance.pendingEscrow()).isEqualTo(Money.ZERO);
        assertThat(balance.totalEarned()).isEqualTo(Money.ZERO);
        assertThat(balance.platformFeesPaid()).isEqualTo(Money.ZERO);
    }

    @Test
    void releaseJobPaysOnlyDuePayoutsAndLeavesHeldOnes() {
        Payout due = payout(PayoutStatus.SCHEDULED, 1000, 100, NOW.minusSeconds(1));
        Payout exactlyNow = payout(PayoutStatus.SCHEDULED, 1000, 100, NOW);
        Payout future = payout(PayoutStatus.SCHEDULED, 1000, 100, NOW.plusSeconds(1));
        Payout held = payout(PayoutStatus.HELD, 1000, 100, NOW.minusSeconds(1));

        int released = release.releaseDue();
        flushAndClear();

        assertThat(released).isEqualTo(2);
        assertThat(payouts.findById(due.getId()).orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payouts.findById(exactlyNow.getId()).orElseThrow().getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payouts.findById(exactlyNow.getId()).orElseThrow().getPaidAt()).isEqualTo(NOW);
        assertThat(payouts.findById(future.getId()).orElseThrow().getStatus()).isEqualTo(PayoutStatus.SCHEDULED);
        assertThat(payouts.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(PayoutStatus.HELD);
        assertThat(service.balance(owner.getId()).availableBalance()).isEqualTo(Money.pln(1800));
    }

    @Test
    void withdrawalReducesTheAvailableBalanceButNotTotalEarned() {
        payout(PayoutStatus.PAID, 1000, 100);

        Withdrawal w = service.withdraw(owner.getId(), new BigDecimal("4.00"), "PL61 1090 1014 0000 0712 1981 2874");
        flushAndClear();

        assertThat(w.getAmount()).isEqualTo(Money.pln(400));
        assertThat(w.maskedIban()).isEqualTo("****2874");
        OwnerPayoutService.Balance balance = service.balance(owner.getId());
        assertThat(balance.availableBalance()).isEqualTo(Money.pln(500));
        assertThat(balance.totalEarned()).isEqualTo(Money.pln(900));
    }

    @Test
    void theWholeAvailableBalanceCanBeWithdrawnButNotMore() {
        payout(PayoutStatus.PAID, 1000, 100);

        service.withdraw(owner.getId(), new BigDecimal("9.00"), IBAN);

        assertThat(service.balance(owner.getId()).availableBalance()).isEqualTo(Money.ZERO);
        assertThatThrownBy(() -> service.withdraw(owner.getId(), new BigDecimal("0.01"), IBAN))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode").isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void pendingEscrowCannotBeWithdrawn() {
        payout(PayoutStatus.SCHEDULED, 1000, 100);

        assertThatThrownBy(() -> service.withdraw(owner.getId(), new BigDecimal("1.00"), IBAN))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invalidAmountsAndIbansAreRejected() {
        payout(PayoutStatus.PAID, 1000, 100);

        assertThatThrownBy(() -> service.withdraw(owner.getId(), BigDecimal.ZERO, IBAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(owner.getId(), new BigDecimal("-1"), IBAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(owner.getId(), new BigDecimal("1.005"), IBAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.withdraw(owner.getId(), BigDecimal.ONE, "PL61109010140000071219812875"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IBAN");
        assertThatThrownBy(() -> service.withdraw(owner.getId(), BigDecimal.ONE, "nonsense")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyListsTheOwnersWithdrawalsNewestFirstAndOnlyTheirs() {
        assertThat(service.history(owner.getId())).isEmpty();
        payout(PayoutStatus.PAID, 1000, 100);
        Withdrawal older = service.withdraw(owner.getId(), new BigDecimal("2.00"), IBAN);
        Withdrawal newer = service.withdraw(owner.getId(), new BigDecimal("3.00"), IBAN);
        em.flush();
        jdbc.update("update withdrawals set created_at = ? where id = ?", java.sql.Timestamp.from(java.time.Instant.parse("2029-11-01T10:00:00Z")), older.getId());
        jdbc.update("update withdrawals set created_at = ? where id = ?", java.sql.Timestamp.from(java.time.Instant.parse("2029-11-02T10:00:00Z")), newer.getId());
        flushAndClear();

        assertThat(service.history(owner.getId())).extracting(Withdrawal::getId).containsExactly(newer.getId(), older.getId());
        assertThat(service.history(persistUser(Role.OWNER).getId())).isEmpty();
    }

    @Test
    void onlyAnOwnerHasAWithdrawalHistory() {
        User driverOnly = persistUser(Role.DRIVER);
        em.flush();

        assertThatThrownBy(() -> service.history(driverOnly.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void onlyAnOwnerCanWithdraw() {
        assertThatThrownBy(() -> service.withdraw(driver.getId(), BigDecimal.ONE, IBAN)).isInstanceOf(ForbiddenException.class);
    }

    private Payout payout(PayoutStatus status, long grossMinor, long commissionMinor) {
        return payout(status, grossMinor, commissionMinor, NOW.plusSeconds(3600));
    }

    private Payout payout(PayoutStatus status, long grossMinor, long commissionMinor, Instant releaseAt) {
        slot++;
        Instant start = Instant.parse("2030-02-01T00:00:00Z").plusSeconds(7200L * slot);
        Reservation reservation = persistReservation(spot, driver, vehicle, start, start.plusSeconds(3600));
        Payout payout = new Payout(reservation, owner, Money.pln(grossMinor), Money.pln(commissionMinor), releaseAt);
        if (status == PayoutStatus.HELD) {
            payout.hold();
        } else if (status == PayoutStatus.PAID) {
            payout.markPaid(NOW, null);
        } else if (status == PayoutStatus.CANCELLED) {
            payout.cancel();
        }
        em.persist(payout);
        em.flush();
        return payout;
    }
}
