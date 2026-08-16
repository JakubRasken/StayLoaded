package io.github.jakubrasken.stayloaded.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SessionMachine {
    private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED_TRANSITIONS = Map.of(
            SessionStatus.STARTING, EnumSet.of(SessionStatus.ACTIVE, SessionStatus.TERMINATED),
            SessionStatus.ACTIVE, EnumSet.of(SessionStatus.RETURNING, SessionStatus.EXPIRED, SessionStatus.TERMINATED),
            SessionStatus.RETURNING, EnumSet.of(SessionStatus.ACTIVE, SessionStatus.TERMINATED),
            SessionStatus.EXPIRED, EnumSet.noneOf(SessionStatus.class),
            SessionStatus.TERMINATED, EnumSet.noneOf(SessionStatus.class)
    );

    private SessionMachine() {
    }

    public static StayLoadedSession create(
            UUID playerId,
            String playerName,
            Instant now,
            Duration duration,
            String note
    ) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return new StayLoadedSession(
                playerId,
                playerName,
                now,
                now.plus(duration),
                now,
                0,
                SessionStatus.STARTING,
                note,
                SessionOutcome.NONE
        );
    }

    public static StayLoadedSession activate(StayLoadedSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (session.status() != SessionStatus.ACTIVE && !now.isBefore(session.expiresAt())) {
            throw new IllegalStateException("Cannot activate a session at or after its deadline");
        }
        return transition(session, SessionStatus.ACTIVE, SessionOutcome.NONE, now);
    }

    public static StayLoadedSession beginReturn(StayLoadedSession session, Instant now) {
        return transition(session, SessionStatus.RETURNING, SessionOutcome.NONE, now);
    }

    public static StayLoadedSession completeReturn(StayLoadedSession session, Instant now) {
        return transition(session, SessionStatus.TERMINATED, SessionOutcome.PLAYER_RETURNED, now);
    }

    public static StayLoadedSession abortReturn(StayLoadedSession session, Instant now) {
        return transition(session, SessionStatus.ACTIVE, SessionOutcome.NONE, now);
    }

    public static StayLoadedSession expire(StayLoadedSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (session.status() == SessionStatus.EXPIRED && session.outcome() == SessionOutcome.TIMEOUT) {
            return session;
        }
        if (now.isBefore(session.expiresAt())) {
            throw new IllegalArgumentException("session cannot expire before its deadline");
        }
        return transition(session, SessionStatus.EXPIRED, SessionOutcome.TIMEOUT, now);
    }

    public static StayLoadedSession terminate(
            StayLoadedSession session,
            SessionOutcome outcome,
            Instant now
    ) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == SessionOutcome.NONE
                || outcome == SessionOutcome.PLAYER_RETURNED
                || outcome == SessionOutcome.TIMEOUT) {
            throw new IllegalArgumentException("Use the specific transition for outcome " + outcome);
        }
        return transition(session, SessionStatus.TERMINATED, outcome, now);
    }

    private static StayLoadedSession transition(
            StayLoadedSession session,
            SessionStatus target,
            SessionOutcome outcome,
            Instant now
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(now, "now");

        if (session.status() == target && session.outcome() == outcome) {
            return session;
        }
        if (!ALLOWED_TRANSITIONS.get(session.status()).contains(target)) {
            throw new IllegalStateException("Cannot transition from " + session.status() + " to " + target);
        }

        Instant transitionTime = now.isBefore(session.updatedAt()) ? session.updatedAt() : now;

        return new StayLoadedSession(
                session.playerId(),
                session.playerName(),
                session.startedAt(),
                session.expiresAt(),
                transitionTime,
                session.revision() + 1,
                target,
                session.note(),
                outcome
        );
    }
}
