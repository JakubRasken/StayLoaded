package io.github.jakubrasken.stayloaded.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsRecorderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsAndPersistsAnonymousCounts() throws IOException {
        MetricsRecorder recorder = new MetricsRecorder(dataFile());
        recorder.record("session_started");
        recorder.record("session_started");
        recorder.record("player_returned");

        recorder.save();
        MetricsRecorder reloaded = new MetricsRecorder(dataFile());

        assertEquals(2L, reloaded.count("session_started"));
        assertEquals(1L, reloaded.count("player_returned"));
    }

    @Test
    void accumulatesAcrossReloads() throws IOException {
        MetricsRecorder recorder = new MetricsRecorder(dataFile());
        recorder.record("session_started");
        recorder.save();

        MetricsRecorder reloaded = new MetricsRecorder(dataFile());
        reloaded.record("session_started");
        reloaded.save();

        assertEquals(2L, new MetricsRecorder(dataFile()).count("session_started"));
    }

    @Test
    void countsAreUnmodifiableSnapshots() throws IOException {
        MetricsRecorder recorder = new MetricsRecorder(dataFile());
        recorder.record("session_started");

        assertThrows(UnsupportedOperationException.class, () -> recorder.counts().put("x", 1L));
    }

    @Test
    void rejectsInvalidDocuments() throws IOException {
        Files.writeString(dataFile(), "not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new MetricsRecorder(dataFile()));
    }

    @Test
    void persistsInOrderAcrossReloads() throws IOException {
        MetricsRecorder recorder = new MetricsRecorder(dataFile());
        Instant start = Instant.parse("2026-08-16T12:00:00Z");
        recorder.record("a", start);
        recorder.record("b", start.plusSeconds(1));
        recorder.save();

        MetricsRecorder reloaded = new MetricsRecorder(dataFile());

        assertEquals(1L, reloaded.count("a"));
        assertEquals(1L, reloaded.count("b"));
    }

    private Path dataFile() {
        return temporaryDirectory.resolve("metrics.json");
    }
}
