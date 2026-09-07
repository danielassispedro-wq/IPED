package iped.viewers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.List;

/** External, append-only revision history. No evidence or index writes. */
final class TranscriptionCorrectionStore {
    static final String DIRECTORY_PROPERTY = "iped.transcriptionCorrectionsDir";
    private final Path database;
    private final boolean mayInitialize;

    TranscriptionCorrectionStore(Path directory, List<File> caseDirectories) throws IOException {
        Path realDirectory = directory.toRealPath();
        if (!Files.isDirectory(realDirectory) || caseDirectories.isEmpty()) {
            throw new IOException("Configure uma pasta externa e abra um caso.");
        }
        database = realDirectory.resolve("transcription_corrections.db");
        mayInitialize = !Files.exists(database);
        if (Files.isSymbolicLink(database)) {
            throw new IOException("O banco não pode ser um link simbólico.");
        }
        Path target = Files.exists(database) ? database.toRealPath() : database;
        for (File caseDirectory : caseDirectories) {
            if (target.startsWith(caseDirectory.toPath().toRealPath())) {
                throw new IOException("O banco de correções deve ficar fora de todos os casos abertos.");
            }
        }
    }

    static final class Entry {
        final String source, item, hash, original, key;
        Entry(String source, String item, String hash, String original) {
            this.source = source;
            this.item = item;
            this.hash = hash;
            this.original = original;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                        (source + "\0" + item + "\0" + hash + "\0" + original).getBytes(StandardCharsets.UTF_8));
                StringBuilder value = new StringBuilder();
                for (byte b : digest) value.append(String.format("%02x", b & 255));
                key = value.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class Revision {
        final int number;
        final String text;
        Revision(int number, String text) {
            this.number = number;
            this.text = text;
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=3000");
            statement.execute("PRAGMA synchronous=FULL");
            int applicationId;
            try (ResultSet result = statement.executeQuery("PRAGMA application_id")) {
                result.next();
                applicationId = result.getInt(1);
            }
            if (applicationId != 1229997380) {
                if (!mayInitialize || applicationId != 0) {
                    throw new SQLException("O arquivo existente não é um banco de correções do IPED.");
                }
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM sqlite_master")) {
                    result.next();
                    if (result.getInt(1) != 0) {
                        throw new SQLException("O banco existente contém objetos desconhecidos.");
                    }
                }
                statement.execute("PRAGMA application_id=1229997380");
            }
            statement.execute("CREATE TABLE IF NOT EXISTS transcription_correction_audit ("
                    + "entry_key TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0), "
                    + "source_uuid TEXT NOT NULL, item_id TEXT NOT NULL, audio_hash TEXT NOT NULL, "
                    + "original_text TEXT NOT NULL, previous_text TEXT NOT NULL, corrected_text TEXT NOT NULL, "
                    + "author TEXT NOT NULL, saved_at_utc TEXT NOT NULL, PRIMARY KEY(entry_key, revision))");
            statement.execute("CREATE TRIGGER IF NOT EXISTS transcription_audit_no_update "
                    + "BEFORE UPDATE ON transcription_correction_audit BEGIN "
                    + "SELECT RAISE(ABORT, 'Audit history is append-only'); END");
            statement.execute("CREATE TRIGGER IF NOT EXISTS transcription_audit_no_delete "
                    + "BEFORE DELETE ON transcription_correction_audit BEGIN "
                    + "SELECT RAISE(ABORT, 'Audit history is append-only'); END");
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    Revision read(Entry entry) throws SQLException {
        try (Connection connection = connect()) {
            return read(connection, entry);
        }
    }

    private Revision read(Connection connection, Entry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision, corrected_text FROM transcription_correction_audit "
                        + "WHERE entry_key=? ORDER BY revision DESC LIMIT 1")) {
            statement.setString(1, entry.key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Revision(result.getInt(1), result.getString(2))
                        : new Revision(0, entry.original);
            }
        }
    }

    Revision save(Entry entry, int expectedRevision, String corrected, String author) throws SQLException {
        if (corrected == null || corrected.isBlank() || corrected.length() > 1000000
                || author == null || author.isBlank()) {
            throw new IllegalArgumentException("Informe a transcrição (até 1.000.000 caracteres) e a identificação do perito.");
        }
        try (Connection connection = connect(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                Revision previous = read(connection, entry);
                if (previous.number != expectedRevision) {
                    throw new SQLException("A transcrição mudou em outra janela. Feche e abra novamente a correção.");
                }
                int number = previous.number + 1;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO transcription_correction_audit "
                        + "(entry_key,revision,source_uuid,item_id,audio_hash,original_text,previous_text,"
                        + "corrected_text,author,saved_at_utc) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, entry.key);
                    statement.setInt(2, number);
                    statement.setString(3, entry.source);
                    statement.setString(4, entry.item);
                    statement.setString(5, entry.hash);
                    statement.setString(6, entry.original);
                    statement.setString(7, previous.text);
                    statement.setString(8, corrected);
                    statement.setString(9, author);
                    statement.setString(10, Instant.now().toString());
                    statement.executeUpdate();
                }
                transaction.execute("COMMIT");
                return new Revision(number, corrected);
            } catch (SQLException | RuntimeException e) {
                try {
                    transaction.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
    }
}
