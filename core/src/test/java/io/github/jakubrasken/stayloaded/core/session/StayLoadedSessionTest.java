package io.github.jakubrasken.stayloaded.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StayLoadedSessionTest {
    private static final Instant START = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void reportsRemainingTimeWithoutGoingNegative() {
        StayLoadedSession session = activeSession();

        assertEquals(Duration.ofMinutes(45), session.remainingAt(START.plus(Duration.ofMinutes(15))));
        assertEquals(Duration.ZERO, session.remainingAt(START.plus(Duration.ofHours(2))));
    }

    @Test
    void identifiesTerminalStates() {
        assertTrue(SessionMachine.expire(activeSession(), START.plus(Duration.ofHours(1))).isTerminal());
        assertTrue(SessionMachine.terminate(
                activeSession(),
                SessionOutcome.ADMIN_TERMINATED,
                START.plus(Duration.ofMinutes(1))
        ).isTerminal());
    }

    @Test
    void rejectsAnInvalidTimeRange() {
        assertThrows(IllegalArgumentException.class, () -> new StayLoadedSession(
                UUID.randomUUID(),
                "Builder",
                START,
                START.minusSeconds(1),
                START,
                0,
                SessionStatus.STARTING,
                "",
                SessionOutcome.NONE
        ));
    }

    private StayLoadedSession session(SessionStatus status) {
        return new StayLoadedSession(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                "Builder",
                START,
                START.plus(Duration.ofHours(1)),
                START,
                0,
                status,
                "Testing",
                SessionOutcome.NONE
        );
    }

    private StayLoadedSession activeSession() {
        return SessionMachine.activate(session(SessionStatus.STARTING), START);
    }
}
