package io.github.jakubrasken.stayloaded.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionMachineTest {
    private static final Instant START = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void completesTheExpectedReturnPath() {
        StayLoadedSession starting = SessionMachine.create(
                UUID.randomUUID(), "Builder", START, Duration.ofHours(2), "Iron farm"
        );
        StayLoadedSession active = SessionMachine.activate(starting, START.plusSeconds(1));
        StayLoadedSession returning = SessionMachine.beginReturn(active, START.plusSeconds(2));
        StayLoadedSession terminated = SessionMachine.completeReturn(returning, START.plusSeconds(3));

        assertEquals(SessionStatus.TERMINATED, terminated.status());
        assertEquals(SessionOutcome.PLAYER_RETURNED, terminated.outcome());
        assertEquals(3, terminated.revision());
    }

    @Test
    void rejectsSkippedTransitionsAndMakesRetriesIdempotent() {
        StayLoadedSession starting = SessionMachine.create(
                UUID.randomUUID(), "Builder", START, Duration.ofHours(2), ""
        );

        assertThrows(IllegalStateException.class, () -> SessionMachine.beginReturn(starting, START));

        StayLoadedSession expired = SessionMachine.expire(
                SessionMachine.activate(starting, START),
                START.plus(Duration.ofHours(2))
        );
        assertSame(expired, SessionMachine.expire(expired, START.plus(Duration.ofHours(3))));
    }

    @Test
    void rejectsNonPositiveDurationsAndClampsBackwardsTime() {
        UUID playerId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> SessionMachine.create(
                playerId, "Builder", START, Duration.ZERO, ""
        ));

        StayLoadedSession starting = SessionMachine.create(
                playerId, "Builder", START, Duration.ofHours(1), ""
        );
        StayLoadedSession activeAfterRollback = SessionMachine.activate(starting, START.minusSeconds(1));
        assertEquals(START, activeAfterRollback.updatedAt());
        StayLoadedSession active = SessionMachine.activate(starting, START);
        assertThrows(IllegalArgumentException.class, () -> SessionMachine.expire(active, START.plus(Duration.ofMinutes(59))));
    }

    @Test
    void refusesActivationAtOrAfterTheDeadline() {
        StayLoadedSession starting = SessionMachine.create(
                UUID.randomUUID(), "Builder", START, Duration.ofHours(1), ""
        );

        assertThrows(
                IllegalStateException.class,
                () -> SessionMachine.activate(starting, START.plus(Duration.ofHours(1)))
        );
    }
}
