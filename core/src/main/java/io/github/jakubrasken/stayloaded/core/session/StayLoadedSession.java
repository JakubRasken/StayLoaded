package io.github.jakubrasken.stayloaded.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StayLoadedSession(
        UUID playerId,
        String playerName,
        Instant startedAt,
        Instant expiresAt,
        Instant updatedAt,
        long revision,
        SessionStatus status,
        String note,
        SessionOutcome outcome
) {
    public StayLoadedSession {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcome, "outcome");
        note = note == null ? "" : note;

        if (playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        if (expiresAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("expiresAt must not be before startedAt");
        }
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("updatedAt must not be before startedAt");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }

        if (status == SessionStatus.EXPIRED && outcome != SessionOutcome.TIMEOUT) {
            throw new IllegalArgumentException("Expired sessions must have the timeout outcome");
        }
        if (status == SessionStatus.TERMINATED
                && (outcome == SessionOutcome.NONE || outcome == SessionOutcome.TIMEOUT)) {
            throw new IllegalArgumentException("Terminated sessions must have a termination outcome");
        }
        if (status != SessionStatus.EXPIRED
                && status != SessionStatus.TERMINATED
                && outcome != SessionOutcome.NONE) {
            throw new IllegalArgumentException("Non-terminal sessions cannot have an outcome");
        }
        if (status == SessionStatus.STARTING && revision != 0) {
            throw new IllegalArgumentException("Starting sessions must have revision zero");
        }
        if (status != SessionStatus.STARTING && revision == 0) {
            throw new IllegalArgumentException("Progressed sessions must have a positive revision");
        }
    }

    public Duration remainingAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!now.isBefore(expiresAt)) {
            return Duration.ZERO;
        }
        return Duration.between(now, expiresAt);
    }

    public boolean isTerminal() {
        return status == SessionStatus.EXPIRED || status == SessionStatus.TERMINATED;
    }
}
