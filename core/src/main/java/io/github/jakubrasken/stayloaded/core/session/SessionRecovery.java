package io.github.jakubrasken.stayloaded.core.session;

import java.time.Instant;
import java.util.Objects;

public final class SessionRecovery {
    private SessionRecovery() {
    }

    public static StayLoadedSession reconcile(StayLoadedSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");

        Instant transitionTime = now.isBefore(session.updatedAt()) ? session.updatedAt() : now;
        return switch (session.status()) {
            case ACTIVE -> session.remainingAt(now).isZero()
                    ? SessionMachine.expire(session, transitionTime)
                    : session;
            case STARTING, RETURNING -> SessionMachine.terminate(
                    session,
                    SessionOutcome.RECOVERY_FAILED,
                    transitionTime
            );
            case EXPIRED, TERMINATED -> session;
        };
    }
}
