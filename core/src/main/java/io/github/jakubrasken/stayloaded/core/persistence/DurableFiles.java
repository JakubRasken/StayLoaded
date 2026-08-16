package io.github.jakubrasken.stayloaded.core.persistence;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class DurableFiles {
    private DurableFiles() {
    }

    public static void writeAtomically(Path target, byte[] content) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = temporaryFile(absoluteTarget);
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        replaceAtomically(temporary, absoluteTarget);
    }

    public static Path temporaryFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        if (isWindows()) {
            moveWithWindowsWriteThrough(source, target);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Durable storage requires atomic moves: " + target, exception);
        }
        forceParentDirectory(target.getParent());
    }

    private static void forceParentDirectory(Path directory) throws IOException {
        if (directory == null || !FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private static void moveWithWindowsWriteThrough(Path source, Path target) throws IOException {
        int flags = WindowsMove.MOVEFILE_REPLACE_EXISTING | WindowsMove.MOVEFILE_WRITE_THROUGH;
        boolean moved = WindowsMove.INSTANCE.MoveFileExW(
                new WString(source.toString()),
                new WString(target.toString()),
                flags
        );
        if (!moved) {
            throw new IOException("MoveFileExW failed with Windows error " + Native.getLastError());
        }
    }

    private interface WindowsMove extends Library {
        WindowsMove INSTANCE = Native.load("kernel32", WindowsMove.class);
        int MOVEFILE_REPLACE_EXISTING = 0x1;
        int MOVEFILE_WRITE_THROUGH = 0x8;

        @SuppressWarnings("checkstyle:MethodName")
        boolean MoveFileExW(WString existingFileName, WString newFileName, int flags);
    }
}
