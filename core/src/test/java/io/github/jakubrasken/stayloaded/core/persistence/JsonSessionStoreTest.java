package io.github.jakubrasken.stayloaded.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jakubrasken.stayloaded.core.session.SessionMachine;
import io.github.jakubrasken.stayloaded.core.session.StayLoadedSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonSessionStoreTest {
    private static final Instant START = Instant.parse("2026-08-13T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsSessionsInStableOrder() throws IOException {
        JsonSessionStore store = store();
        StayLoadedSession second = session("ffffffff-ffff-ffff-ffff-ffffffffffff", "Second");
        StayLoadedSession first = session("00000000-0000-0000-0000-000000000001", "First");

        store.save(List.of(second, first));
        SessionLoadResult result = store.load();

        assertFalse(result.recovered());
        assertEquals(SessionLoadSource.PRIMARY, result.source());
        assertEquals(List.of(first, second), result.sessions());
    }

    @Test
    void recoversThePreviousGenerationWhenPrimaryIsCorrupt() throws IOException {
        JsonSessionStore store = store();
        StayLoadedSession previous = session("00000000-0000-0000-0000-000000000001", "Previous");
        StayLoadedSession current = session("00000000-0000-0000-0000-000000000002", "Current");
        store.save(List.of(previous));
        store.save(List.of(current));
        Files.writeString(dataFile(), "not json", StandardCharsets.UTF_8);

        SessionLoadResult result = store.load();

        assertTrue(result.recovered());
        assertEquals(SessionLoadSource.BACKUP, result.source());
        assertEquals(List.of(previous), result.sessions());

        store.save(result.sessions());
        assertEquals(List.of(previous), store.load().sessions());
    }

    @Test
    void recoversACompletedTemporaryFirstWrite() throws IOException {
        StayLoadedSession session = session("00000000-0000-0000-0000-000000000001", "Builder");
        store().save(List.of(session));
        Files.move(dataFile(), dataFile().resolveSibling("sessions.json.tmp"));

        SessionLoadResult result = store().load();

        assertEquals(SessionLoadSource.TEMPORARY, result.source());
        assertEquals(List.of(session), result.sessions());
    }

    @Test
    void rejectsDuplicatePlayersBeforeWriting() {
        JsonSessionStore store = store();
        StayLoadedSession session = session("00000000-0000-0000-0000-000000000001", "Builder");

        assertThrows(IllegalArgumentException.class, () -> store.save(List.of(session, session)));
    }

    @Test
    void failsWhenPrimaryAndBackupAreBothCorrupt() throws IOException {
        Files.writeString(dataFile(), "bad", StandardCharsets.UTF_8);
        Files.writeString(dataFile().resolveSibling("sessions.json.bak"), "also bad", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store().load());
    }

    @Test
    void refusesToOverwriteTheOnlyCorruptGeneration() throws IOException {
        Files.writeString(dataFile(), "bad", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store().save(List.of(
                session("00000000-0000-0000-0000-000000000001", "Builder")
        )));
    }

    @Test
    void roundTripsCheckpointedAt() throws IOException {
        JsonSessionStore store = store();
        StayLoadedSession active = session("00000000-0000-0000-0000-000000000001", "Builder");
        StayLoadedSession checkpointed = SessionMachine.checkpoint(active, START.plusSeconds(30));

        store.save(List.of(checkpointed));
        SessionLoadResult result = store.load();

        assertEquals(List.of(checkpointed), result.sessions());
    }

    @Test
    void readsLegacySessionsWithoutCheckpointedAt() throws IOException {
        String legacy = """
                {
                  "schemaVersion": 1,
                  "sessions": [
                    {
                      "playerId": "00000000-0000-0000-0000-000000000001",
                      "playerName": "Builder",
                      "startedAt": "2026-08-13T12:00:00Z",
                      "expiresAt": "2026-08-13T13:00:00Z",
                      "updatedAt": "2026-08-13T12:00:01Z",
                      "revision": 1,
                      "status": "ACTIVE",
                      "note": "Testing",
                      "outcome": "NONE"
                    }
                  ]
                }
                """;
        Files.writeString(dataFile(), legacy, StandardCharsets.UTF_8);

        SessionLoadResult result = store().load();

        assertEquals(1, result.sessions().size());
        assertEquals(null, result.sessions().get(0).checkpointedAt());
    }

    @Test
    void roundTripsRespawnCount() throws IOException {
        JsonSessionStore store = store();
        StayLoadedSession active = session("00000000-0000-0000-0000-000000000001", "Builder");
        StayLoadedSession respawned = SessionMachine.respawn(active, START.plusSeconds(30));

        store.save(List.of(respawned));
        SessionLoadResult result = store.load();

        assertEquals(List.of(respawned), result.sessions());
    }

    private JsonSessionStore store() {
        return new JsonSessionStore(dataFile());
    }

    private Path dataFile() {
        return temporaryDirectory.resolve("sessions.json");
    }

    private StayLoadedSession session(String playerId, String playerName) {
        return SessionMachine.activate(
                SessionMachine.create(
                        UUID.fromString(playerId),
                        playerName,
                        START,
                        Duration.ofHours(1),
                        "Testing"
                ),
                START.plusSeconds(1)
        );
    }
}
