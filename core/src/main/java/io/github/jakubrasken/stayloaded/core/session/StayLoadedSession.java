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
        SessionStatus status,
        String reason
) {
    public StayLoadedSession {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;

        if (playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        if (expiresAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("expiresAt must not be before startedAt");
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
