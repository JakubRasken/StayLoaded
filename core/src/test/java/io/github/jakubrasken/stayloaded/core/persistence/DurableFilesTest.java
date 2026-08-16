package io.github.jakubrasken.stayloaded.core.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFilesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writeAtomicallyReplacesExistingContent() throws IOException {
        Path target = temporaryDirectory.resolve("data.bin");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        DurableFiles.writeAtomically(target, "new-content".getBytes(StandardCharsets.UTF_8));

        assertEquals("new-content", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(DurableFiles.temporaryFile(target)));
    }

    @Test
    void writeAtomicallyCreatesMissingParentDirectories() throws IOException {
        Path target = temporaryDirectory.resolve("deep/nested/data.bin");

        DurableFiles.writeAtomically(target, "content".getBytes(StandardCharsets.UTF_8));

        assertEquals("content", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void crashMidWriteLeavesPreviousTargetIntact() throws IOException {
        Path target = temporaryDirectory.resolve("data.bin");
        DurableFiles.writeAtomically(target, "original".getBytes(StandardCharsets.UTF_8));
        Path temporary = DurableFiles.temporaryFile(target);

        Files.writeString(temporary, "partial-new", StandardCharsets.UTF_8);

        assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporary));
    }

    @Test
    void writesAreRecoverableFromATemporaryFile() throws IOException {
        Path target = temporaryDirectory.resolve("data.bin");
        byte[] intended = "recoverable".getBytes(StandardCharsets.UTF_8);
        DurableFiles.writeAtomically(target, intended);
        Path temporary = DurableFiles.temporaryFile(target);

        Files.move(target, temporary);

        assertFalse(Files.exists(target));
        assertArrayEquals(intended, Files.readAllBytes(temporary));
    }
}
