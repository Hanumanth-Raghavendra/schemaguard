package io.github.hanumanthraghavendra.schemaguard.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * SQL read from a file, or from standard input when the path is {@code -}.
 *
 * @param name how messages name it: the path as given, or {@code stdin}
 * @param sql  the text, without a byte order mark
 */
record Source(String name, String sql) {

    static final String STDIN = "-";

    static Source read(String path, Io io) {
        if (path.equals(STDIN)) {
            try {
                return new Source("stdin", decode("stdin", io.in().readAllBytes()));
            } catch (IOException e) {
                throw new InputException("Cannot read standard input: " + e.getMessage());
            }
        }
        Path file = Path.of(path);
        if (Files.isDirectory(file)) {
            throw new InputException(path + " is a folder, not a SQL file.");
        }
        try {
            return new Source(path, decode(path, Files.readAllBytes(file)));
        } catch (NoSuchFileException e) {
            throw new InputException(path + ": no such file.");
        } catch (AccessDeniedException e) {
            throw new InputException(path + ": permission denied.");
        } catch (IOException e) {
            throw new InputException("Cannot read " + path + ": " + e.getMessage());
        }
    }

    /** UTF-8, strictly: a file in another encoding is refused rather than read as garbage. */
    private static String decode(String name, byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new InputException(name + " is not UTF-8 text.");
        }
    }
}
