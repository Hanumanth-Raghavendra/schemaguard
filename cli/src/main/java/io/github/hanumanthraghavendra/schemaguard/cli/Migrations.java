package io.github.hanumanthraghavendra.schemaguard.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The SQL files of a migrations folder in the order they run. Flyway's versioned files
 * ({@code V2__add_orders.sql}, {@code V2.1__...}, {@code V20261125__...}) are ordered by version,
 * part by part as numbers; its repeatable ({@code R__}) and undo ({@code U2__}) files are left out
 * and named in {@link Folder#skipped()}. A folder of other names ({@code 001_init.sql}) is ordered
 * by name, with runs of digits compared as numbers.
 */
final class Migrations {

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.*\\.sql");
    private static final Pattern FLYWAY_OTHER = Pattern.compile("(?:R|U\\d+(?:[._]\\d+)*)__.*\\.sql");

    private Migrations() {}

    /**
     * @param files   the files to run, in order
     * @param skipped Flyway files that are not versioned migrations, by name
     */
    record Folder(List<Path> files, List<String> skipped) {}

    static Folder read(Path folder) {
        if (!Files.isDirectory(folder)) {
            throw new InputException(folder + " is not a folder.");
        }
        List<Path> sql;
        try (Stream<Path> entries = Files.list(folder)) {
            sql = entries.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .toList();
        } catch (IOException e) {
            throw new InputException("Cannot list " + folder + ": " + e.getMessage());
        }
        List<Path> versioned = new ArrayList<>();
        List<Path> other = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path file : sql) {
            String name = file.getFileName().toString();
            if (VERSIONED.matcher(name).matches()) {
                versioned.add(file);
            } else if (FLYWAY_OTHER.matcher(name).matches()) {
                skipped.add(name);
            } else {
                other.add(file);
            }
        }
        skipped.sort(Comparator.naturalOrder());
        if (versioned.isEmpty() && other.isEmpty()) {
            throw new InputException(folder + " has no .sql migrations.");
        }
        if (!versioned.isEmpty() && !other.isEmpty()) {
            throw new InputException(folder + " mixes Flyway files (" + name(versioned.get(0)) + ") with others ("
                    + name(other.get(0)) + "); the order they run in is unclear.");
        }
        if (versioned.isEmpty()) {
            return new Folder(other.stream().sorted(Comparator.comparing(Migrations::name, Migrations::natural)).toList(), skipped);
        }
        List<Path> ordered = versioned.stream()
                .sorted(Comparator.comparing(Migrations::version, Migrations::versions))
                .toList();
        for (int i = 1; i < ordered.size(); i++) {
            if (versions(version(ordered.get(i - 1)), version(ordered.get(i))) == 0) {
                throw new InputException("Two migrations have version " + version(ordered.get(i)) + ": "
                        + name(ordered.get(i - 1)) + " and " + name(ordered.get(i)) + ".");
            }
        }
        return new Folder(ordered, skipped);
    }

    private static String name(Path file) {
        return file.getFileName().toString();
    }

    private static String version(Path file) {
        Matcher match = VERSIONED.matcher(name(file));
        if (!match.matches()) {
            throw new IllegalStateException("not versioned: " + file);
        }
        return match.group(1);
    }

    /** Flyway's order: part by part as numbers, so 1.10 comes after 1.9 and 1.0 equals 1. */
    static int versions(String a, String b) {
        String[] left = a.split("[._]");
        String[] right = b.split("[._]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int compared = digits(i < left.length ? left[i] : "0", i < right.length ? right[i] : "0");
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /** Names with runs of digits compared as numbers: 2_x before 10_x. */
    static int natural(String a, String b) {
        Matcher left = CHUNK.matcher(a);
        Matcher right = CHUNK.matcher(b);
        while (left.find() && right.find()) {
            String l = left.group();
            String r = right.group();
            boolean numbers = Character.isDigit(l.charAt(0)) && Character.isDigit(r.charAt(0));
            int compared = numbers ? digits(l, r) : l.compareTo(r);
            if (compared != 0) {
                return compared;
            }
        }
        return a.compareTo(b);
    }

    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");

    /** Two runs of digits as numbers of any length. */
    private static int digits(String a, String b) {
        String left = a.replaceFirst("^0+(?=.)", "");
        String right = b.replaceFirst("^0+(?=.)", "");
        return left.length() != right.length() ? Integer.compare(left.length(), right.length()) : left.compareTo(right);
    }
}
