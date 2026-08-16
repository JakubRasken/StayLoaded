package io.github.jakubrasken.stayloaded.core.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.jakubrasken.stayloaded.core.session.SessionOutcome;
import io.github.jakubrasken.stayloaded.core.session.SessionStatus;
import io.github.jakubrasken.stayloaded.core.session.StayLoadedSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JsonSessionStore {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataFile;
    private final Path backupFile;

    public JsonSessionStore(Path dataFile) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile").toAbsolutePath();
        this.backupFile = dataFile.resolveSibling(dataFile.getFileName() + ".bak").toAbsolutePath();
    }

    public SessionLoadResult load() throws IOException {
        Path temporaryFile = DurableFiles.temporaryFile(dataFile);
        List<Throwable> failures = new ArrayList<>();

        if (!Files.notExists(dataFile)) {
            try {
                return new SessionLoadResult(read(dataFile), SessionLoadSource.PRIMARY);
            } catch (IOException | RuntimeException failure) {
                failures.add(failure);
            }
        }

        if (!Files.notExists(temporaryFile)) {
            try {
                return new SessionLoadResult(read(temporaryFile), SessionLoadSource.TEMPORARY);
            } catch (IOException | RuntimeException failure) {
                failures.add(failure);
            }
        }

        if (!Files.notExists(backupFile)) {
            try {
                return new SessionLoadResult(read(backupFile), SessionLoadSource.BACKUP);
            } catch (IOException | RuntimeException failure) {
                failures.add(failure);
            }
        }

        if (failures.isEmpty()) {
            return new SessionLoadResult(List.of(), SessionLoadSource.EMPTY);
        }

        IOException failure = invalidStore("No valid session store generation is available", failures.get(0));
        failures.stream().skip(1).forEach(failure::addSuppressed);
        throw failure;
    }

    public void save(Collection<StayLoadedSession> sessions) throws IOException {
        Objects.requireNonNull(sessions, "sessions");
        List<StayLoadedSession> ordered = sessions.stream()
                .sorted(Comparator.comparing(session -> session.playerId().toString()))
                .toList();
        ensureUniquePlayers(ordered);

        List<PersistedSession> persistedSessions = ordered.stream()
                .map(PersistedSession::fromSession)
                .toList();
        byte[] content = (GSON.toJson(new SessionDocument(SCHEMA_VERSION, persistedSessions)) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);

        Path parent = dataFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.notExists(dataFile)) {
            try {
                read(dataFile);
                DurableFiles.writeAtomically(backupFile, Files.readAllBytes(dataFile));
            } catch (IOException | RuntimeException primaryFailure) {
                if (Files.notExists(backupFile)) {
                    throw invalidStore("Refusing to replace an invalid primary without a valid backup", primaryFailure);
                }
                try {
                    read(backupFile);
                } catch (IOException | RuntimeException backupFailure) {
                    IOException failure = invalidStore(
                            "Refusing to save because primary and backup are invalid",
                            primaryFailure
                    );
                    failure.addSuppressed(backupFailure);
                    throw failure;
                }
            }
        }
        DurableFiles.writeAtomically(dataFile, content);
    }

    private List<StayLoadedSession> read(Path path) throws IOException {
        try {
            SessionDocument document = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SessionDocument.class);
            if (document == null || document.schemaVersion() != SCHEMA_VERSION || document.sessions() == null) {
                throw new IOException("Unsupported or incomplete session document: " + path);
            }

            List<StayLoadedSession> sessions = new ArrayList<>(document.sessions().size());
            for (PersistedSession persisted : document.sessions()) {
                sessions.add(persisted.toSession());
            }
            ensureUniquePlayers(sessions);
            return List.copyOf(sessions);
        } catch (JsonParseException | IllegalArgumentException | NullPointerException exception) {
            throw invalidStore("Invalid session document: " + path, exception);
        }
    }

    private static void ensureUniquePlayers(List<StayLoadedSession> sessions) {
        Set<UUID> playerIds = new HashSet<>();
        for (StayLoadedSession session : sessions) {
            if (!playerIds.add(session.playerId())) {
                throw new IllegalArgumentException("Duplicate session for player " + session.playerId());
            }
        }
    }

    private static IOException invalidStore(String message, Throwable cause) {
        return new IOException(message, cause);
    }

    private record SessionDocument(int schemaVersion, List<PersistedSession> sessions) {
    }

    private record PersistedSession(
            String playerId,
            String playerName,
            String startedAt,
            String expiresAt,
            String updatedAt,
            long revision,
            String status,
            String note,
            String outcome,
            String checkpointedAt
    ) {
        private static PersistedSession fromSession(StayLoadedSession session) {
            return new PersistedSession(
                    session.playerId().toString(),
                    session.playerName(),
                    session.startedAt().toString(),
                    session.expiresAt().toString(),
                    session.updatedAt().toString(),
                    session.revision(),
                    session.status().name(),
                    session.note(),
                    session.outcome().name(),
                    session.checkpointedAt() == null ? null : session.checkpointedAt().toString()
            );
        }

        private StayLoadedSession toSession() {
            return new StayLoadedSession(
                    UUID.fromString(playerId),
                    playerName,
                    Instant.parse(startedAt),
                    Instant.parse(expiresAt),
                    Instant.parse(updatedAt),
                    revision,
                    SessionStatus.valueOf(status),
                    note,
                    SessionOutcome.valueOf(outcome),
                    checkpointedAt == null ? null : Instant.parse(checkpointedAt)
            );
        }
    }
}
