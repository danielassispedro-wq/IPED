package iped.viewers;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TranscriptionCorrectionStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private TranscriptionCorrectionStore store(Path directory) throws Exception {
        return new TranscriptionCorrectionStore(directory,
                Collections.singletonList(temporary.newFolder().getCanonicalFile()));
    }

    @Test public void revisionsPersistAndAuditPreservesOriginal() throws Exception {
        Path directory = temporary.newFolder().toPath();
        TranscriptionCorrectionStore store = store(directory);
        TranscriptionCorrectionStore.Entry entry =
                new TranscriptionCorrectionStore.Entry("source-a", "12", "abcd", "Original\náudio");
        assertEquals(0, store.read(entry).number);
        String corrected = "<script>alert('x')</script>\nCorreção \uD83D\uDE00";
        store.save(entry, 0, corrected, "Perito A");
        store.save(entry, 1, "Segunda revisão", "Perito B");
        assertEquals("Segunda revisão", store(directory).read(entry).text);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("transcription_corrections.db").toUri());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT original_text,previous_text,corrected_text,author,saved_at_utc "
                        + "FROM transcription_correction_audit ORDER BY revision")) {
            assertTrue(rows.next());
            assertEquals(entry.original, rows.getString(1));
            assertEquals(entry.original, rows.getString(2));
            assertEquals(corrected, rows.getString(3));
            assertEquals("Perito A", rows.getString(4));
            assertTrue(rows.getString(5).endsWith("Z"));
            assertTrue(rows.next());
            assertEquals(corrected, rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test public void staleSaveDoesNotOverwriteOrAppend() throws Exception {
        TranscriptionCorrectionStore store = store(temporary.newFolder().toPath());
        TranscriptionCorrectionStore.Entry entry =
                new TranscriptionCorrectionStore.Entry("source", "1", "abcd", "original");
        store.save(entry, 0, "first", "A");
        try {
            store.save(entry, 0, "stale", "B");
            fail("Expected a conflict");
        } catch (SQLException expected) {
            assertEquals(1, store.read(entry).number);
            assertEquals("first", store.read(entry).text);
        }
    }

    @Test public void identitySeparatesSourcesAndOriginalVersions() throws Exception {
        TranscriptionCorrectionStore store = store(temporary.newFolder().toPath());
        TranscriptionCorrectionStore.Entry first =
                new TranscriptionCorrectionStore.Entry("source-a", "1", "abcd", "original");
        store.save(first, 0, "corrected", "A");
        assertEquals(0, store.read(
                new TranscriptionCorrectionStore.Entry("source-b", "1", "abcd", "original")).number);
        assertEquals(0, store.read(
                new TranscriptionCorrectionStore.Entry("source-a", "1", "abcd", "new original")).number);
    }

    @Test public void rejectsDatabaseInsideCase() throws Exception {
        Path caseDirectory = temporary.newFolder().toPath();
        Path nested = Files.createDirectory(caseDirectory.resolve("nested"));
        try {
            new TranscriptionCorrectionStore(nested, Collections.singletonList(caseDirectory.toFile()));
            fail("Expected path rejection");
        } catch (java.io.IOException expected) {
            assertFalse(Files.exists(nested.resolve("transcription_corrections.db")));
        }
    }

    @Test public void refusesExistingUnrelatedDatabase() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path database = directory.resolve("transcription_corrections.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated(value TEXT)");
        }
        byte[] before = Files.readAllBytes(database);
        try {
            store(directory).read(new TranscriptionCorrectionStore.Entry("s", "1", "a", "text"));
            fail("Expected refusal");
        } catch (SQLException expected) {
            assertArrayEquals(before, Files.readAllBytes(database));
        }
    }

    @Test public void auditRejectsUpdateAndDelete() throws Exception {
        Path directory = temporary.newFolder().toPath();
        TranscriptionCorrectionStore store = store(directory);
        store.save(new TranscriptionCorrectionStore.Entry("s", "1", "a", "original"), 0, "corrected", "A");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("transcription_corrections.db").toUri());
                Statement statement = connection.createStatement()) {
            for (String sql : new String[] {
                    "DELETE FROM transcription_correction_audit",
                    "UPDATE transcription_correction_audit SET corrected_text='changed'" }) {
                try {
                    statement.executeUpdate(sql);
                    fail("Expected append-only protection");
                } catch (SQLException expected) {
                    assertTrue(expected.getMessage().contains("append-only"));
                }
            }
        }
    }
}
