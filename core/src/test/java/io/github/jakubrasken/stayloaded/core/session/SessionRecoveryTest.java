package io.github.jakubrasken.stayloaded.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionRecoveryTest {
    private static final Instant START = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void keepsAnUnexpiredActiveSessionRestorable() {
        StayLoadedSession active = activeSession();

        assertSame(active, SessionRecovery.reconcile(active, START.plus(Duration.ofMinutes(30))));
    }

    @Test
    void countsServerDowntimeTowardTheAbsoluteExpiry() {
        StayLoadedSession recovered = SessionRecovery.reconcile(
                activeSession(),
                START.plus(Duration.ofHours(2))
        );

        assertEquals(SessionStatus.EXPIRED, recovered.status());
        assertEquals(SessionOutcome.TIMEOUT, recovered.outcome());
    }

    @Test
    void failsClosedForInterruptedIdentityTransitions() {
        StayLoadedSession starting = startingSession();
        StayLoadedSession returning = SessionMachine.beginReturn(
                SessionMachine.activate(starting, START),
                START.plusSeconds(1)
        );

        assertEquals(
                SessionOutcome.RECOVERY_FAILED,
                SessionRecovery.reconcile(starting, START.plusSeconds(2)).outcome()
        );
        assertEquals(
                SessionOutcome.RECOVERY_FAILED,
                SessionRecovery.reconcile(returning, START.plusSeconds(2)).outcome()
        );
    }

    @Test
    void failsClosedEvenWhenTheWallClockMovedBackwards() {
        StayLoadedSession returning = SessionMachine.beginReturn(
                activeSession(),
                START.plusSeconds(10)
        );

        StayLoadedSession recovered = SessionRecovery.reconcile(returning, START.plusSeconds(5));

        assertEquals(SessionStatus.TERMINATED, recovered.status());
        assertEquals(SessionOutcome.RECOVERY_FAILED, recovered.outcome());
        assertEquals(returning.updatedAt(), recovered.updatedAt());
    }

    @Test
    void aReturningPlayerWinsAfterActiveRecoveryAndClockRollback() {
        StayLoadedSession active = SessionMachine.activate(startingSession(), START.plusSeconds(10));
        StayLoadedSession recovered = SessionRecovery.reconcile(active, START.plusSeconds(5));

        StayLoadedSession returning = SessionMachine.beginReturn(recovered, START.plusSeconds(6));

        assertEquals(SessionStatus.RETURNING, returning.status());
        assertEquals(active.updatedAt(), returning.updatedAt());
    }

    private StayLoadedSession activeSession() {
        return SessionMachine.activate(startingSession(), START);
    }

    private StayLoadedSession startingSession() {
        return SessionMachine.create(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                "Builder",
                START,
                Duration.ofHours(1),
                "Testing"
        );
    }
}
