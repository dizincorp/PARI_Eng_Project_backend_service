package pl.pollub.pari.support.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.common.service.PlatformSettings;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.Sanction;
import pl.pollub.pari.support.domain.SanctionType;
import pl.pollub.pari.support.repository.SanctionRepository;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.UserStatus;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Warnings, blocks and unblocks of user accounts, and the moderation lookup (PARI-82, F22). Sanctions are history and are
 * never deleted: an unblock is a new UNBLOCK entry.
 *
 * <p>Escalation policy: a WARNING counts until the account is unblocked. When the warnings since the last unblock reach
 * the {@code warning_limit} platform setting (default 3), the account is blocked automatically and a BLOCK entry with the
 * reason "Warning limit reached" is written. After an unblock the count starts again from zero. The rule lives here, not in
 * the frontend.
 */
@Service
public class ModerationService {

    private final SanctionRepository sanctions;
    private final UserRepository users;
    private final AuditTrail audit;
    private final PlatformSettings settings;
    private final SupportTicketService ticketService;

    public ModerationService(SanctionRepository sanctions, UserRepository users, AuditTrail audit, PlatformSettings settings,
                             SupportTicketService ticketService) {
        this.sanctions = sanctions;
        this.users = users;
        this.audit = audit;
        this.settings = settings;
        this.ticketService = ticketService;
    }

    /** One sanction of the history. {@code ticketId} is null for a sanction that was not issued from a ticket. */
    public record SanctionView(UUID id, SanctionType type, String reason, UUID issuedById, UUID ticketId, Instant createdAt) {
    }

    /** The moderation state of an account: whether it is blocked, the warnings that count now, and the whole history, newest first. */
    public record ModerationView(UUID userId, String displayName, String email, boolean blocked, int warningCount,
                                 int warningLimit, List<SanctionView> history) {
    }

    /** An entry of the blocked-accounts list. */
    public record BlockedUserView(UUID userId, String displayName, String email, Instant blockedSince, String reason,
                                  int warningCount) {
    }

    /**
     * Applies a sanction and writes it to the history. Rejects a block of a blocked account and an unblock of an account
     * that is not blocked (409). Reaching the warning limit blocks the account.
     *
     * @return true when this warning pushed the account over the limit and blocked it automatically
     */
    @Transactional
    public boolean apply(UUID actorId, User target, Report ticket, SanctionType type, String reason) {
        User actor = users.getReferenceById(actorId);
        switch (type) {
            case BLOCK -> {
                if (target.isBlocked()) {
                    throw new ConflictException("ALREADY_BLOCKED", "The account is already blocked");
                }
                target.block();
            }
            case UNBLOCK -> {
                if (!target.isBlocked()) {
                    throw new ConflictException("NOT_BLOCKED", "The account is not blocked");
                }
                target.unblock();
            }
            case WARNING -> {
                // saved first so that it is part of the count below
            }
        }
        sanctions.saveAndFlush(new Sanction(target, ticket, actor, type, reason));
        if (type == SanctionType.WARNING && !target.isBlocked()
                && warningsSinceLastUnblock(target.getId()) >= settings.integer("warning_limit")) {
            target.block();
            sanctions.save(new Sanction(target, ticket, actor, SanctionType.BLOCK, "Warning limit reached"));
            return true;
        }
        return false;
    }

    /** The moderation state of an account. Support only. */
    @Transactional(readOnly = true)
    public ModerationView moderation(UUID supportId, UUID userId) {
        ticketService.requireSupport(supportId);
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        List<Sanction> history = sanctions.findByUserIdOrderByCreatedAtDesc(userId);
        return new ModerationView(user.getId(), user.getFirstName() + " " + user.getLastName(), user.getEmail(),
                user.isBlocked(), countSinceLastUnblock(history), settings.integer("warning_limit"),
                history.stream().map(ModerationService::view).toList());
    }

    /** Accounts that are blocked now, by last name. Support only. */
    @Transactional(readOnly = true)
    public List<BlockedUserView> blockedUsers(UUID supportId, int page, int size) {
        ticketService.requireSupport(supportId);
        return users.findByStatusOrderByLastNameAscFirstNameAsc(UserStatus.BLOCKED, PageRequest.of(page, size)).stream()
                .map(u -> {
                    Sanction block = sanctions.findFirstByUserIdAndTypeOrderByCreatedAtDesc(u.getId(), SanctionType.BLOCK).orElse(null);
                    return new BlockedUserView(u.getId(), u.getFirstName() + " " + u.getLastName(), u.getEmail(),
                            block == null ? null : block.getCreatedAt(), block == null ? null : block.getReason(),
                            warningsSinceLastUnblock(u.getId()));
                }).toList();
    }

    /** Unblocks an account without a ticket: an UNBLOCK entry is written and the earlier sanctions stay. A reason is required. */
    @Transactional
    public ModerationView unblock(UUID supportId, UUID userId, String reason) {
        ticketService.requireSupport(supportId);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to unblock an account");
        }
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        apply(supportId, user, null, SanctionType.UNBLOCK, reason);
        audit.record(supportId, "User", userId, "SUPPORT_UNBLOCK", Map.of("reason", reason, "outcome", "SUCCEEDED"));
        return moderation(supportId, userId);
    }

    private int warningsSinceLastUnblock(UUID userId) {
        return countSinceLastUnblock(sanctions.findByUserIdOrderByCreatedAtDesc(userId));
    }

    /** {@code newestFirst} is the history; counts the WARNING entries until the first UNBLOCK. */
    private static int countSinceLastUnblock(List<Sanction> newestFirst) {
        int count = 0;
        for (Sanction s : newestFirst) {
            if (s.getType() == SanctionType.UNBLOCK) {
                break;
            }
            if (s.getType() == SanctionType.WARNING) {
                count++;
            }
        }
        return count;
    }

    private static SanctionView view(Sanction s) {
        return new SanctionView(s.getId(), s.getType(), s.getReason(), s.getIssuedBy().getId(),
                s.getReport() == null ? null : s.getReport().getId(), s.getCreatedAt());
    }
}
