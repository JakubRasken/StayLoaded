package io.github.jakubrasken.stayloaded.core.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.github.jakubrasken.stayloaded.core.persistence.DurableFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable, operator-visible counters for the server-independent core.
 *
 * <p>The recorder appends to an in-memory event log and persists a snapshot
 * atomically (see {@link DurableFiles}) so a crash cannot corrupt it. Only
 * anonymous aggregate counts are stored; no player names or UUIDs.
 */
public final class MetricsRecorder {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();

    private final Path dataFile;
    private final MetricsSnapshot snapshot = new MetricsSnapshot();

    public MetricsRecorder(Path dataFile) throws IOException {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile").toAbsolutePath();
        load();
    }

    public synchronized void record(String eventName) {
        record(eventName, Instant.now());
    }

    public synchronized void record(String eventName, Instant at) {
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(at, "at");
        snapshot.counts.merge(eventName, 1L, Long::sum);
        snapshot.events.add(new MetricsEvent(eventName, at));
        if (snapshot.events.size() > 256) {
            snapshot.events.remove(0);
        }
    }

    public synchronized Map<String, Long> counts() {
        return Map.copyOf(snapshot.counts);
    }

    public synchronized long count(String eventName) {
        return snapshot.counts.getOrDefault(eventName, 0L);
    }

    public synchronized void save() throws IOException {
        String content = GSON.toJson(new MetricsDocument(SCHEMA_VERSION, snapshot.counts, snapshot.events))
                + System.lineSeparator();
        DurableFiles.writeAtomically(dataFile, content.getBytes(StandardCharsets.UTF_8));
    }

    private void load() throws IOException {
        if (Files.notExists(dataFile)) {
            return;
        }
        try {
            MetricsDocument document = GSON.fromJson(
                    Files.readString(dataFile, StandardCharsets.UTF_8),
                    MetricsDocument.class
            );
            if (document == null || document.schemaVersion != SCHEMA_VERSION) {
                throw new IOException("Unsupported metrics document: " + dataFile);
            }
            if (document.counts != null) {
                snapshot.counts.putAll(document.counts);
            }
            if (document.events != null) {
                snapshot.events.addAll(document.events);
                snapshot.events.sort(Comparator.comparing(MetricsEvent::at));
                while (snapshot.events.size() > 256) {
                    snapshot.events.remove(0);
                }
            }
        } catch (com.google.gson.JsonParseException exception) {
            throw new IOException("Invalid metrics document: " + dataFile, exception);
        }
    }

    private static final class MetricsSnapshot {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final List<MetricsEvent> events = new ArrayList<>();
    }

    private record MetricsEvent(String name, Instant at) {
    }

    private record MetricsDocument(int schemaVersion, Map<String, Long> counts, List<MetricsEvent> events) {
    }

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value == null ? null : value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}
