package io.github.mykoss.asyncsql;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for libasynql-style PSF declarations (not a PHP API implementation). */
final class PsfParser {
    private static final Pattern VARIABLE = Pattern.compile("^([A-Za-z_][A-Za-z_0-9]*)\\s+(\\S+)(?:\\s+(.*))?$");

    static SqlCatalog parse(String content, String targetDialect) {
        if (content == null) throw new IllegalArgumentException("PSF content is required");
        String wanted = targetDialect.toLowerCase(Locale.ROOT);
        if (!wanted.equals("mysql") && !wanted.equals("sqlite") && !wanted.equals("mariadb")) {
            throw new IllegalArgumentException("Unsupported SQL dialect: " + targetDialect);
        }
        SqlCatalog output = new SqlCatalog();
        Deque<Frame> frames = new ArrayDeque<>();
        String fileDialect = null;
        String[] lines = content.split("\\R", -1);
        for (int number = 0; number < lines.length; number++) {
            String line = lines[number].trim();
            if (line.isEmpty()) continue;
            int lineNumber = number + 1;
            if (line.startsWith("-- #")) {
                String command = line.substring(4).trim();
                if (command.isEmpty()) continue;
                char action = command.charAt(0);
                String args = command.substring(1).trim();
                switch (action) {
                    case '!' -> {
                        if (fileDialect != null || !frames.isEmpty() || !output.asMap().isEmpty()) {
                            throw error(lineNumber, "Dialect declaration must occur exactly once at the beginning");
                        }
                        fileDialect = args.toLowerCase(Locale.ROOT);
                        if (!fileDialect.equals(wanted)) {
                            throw error(lineNumber, "Wrong dialect: expected " + wanted + " but file declares " + args);
                        }
                    }
                    case '{' -> {
                        if (fileDialect == null) throw error(lineNumber, "Declare dialect before queries");
                        if (args.isBlank() || args.matches(".*\\s+.*")) {
                            throw error(lineNumber, "Invalid group/query name: " + args);
                        }
                        if (!frames.isEmpty()) {
                            Frame parent = frames.peek();
                            if (parent.isQuery() || parent.childEnded) {
                                if (parent.isQuery()) throw error(lineNumber, "Cannot create group inside query");
                            }
                        }
                        frames.push(new Frame(args));
                    }
                    case '}' -> {
                        if (frames.isEmpty()) throw error(lineNumber, "Unmatched closing brace");
                        Frame finished = frames.pop();
                        if (finished.isQuery()) {
                            finished.flush(lineNumber);
                            String path = fullName(frames, finished.name);
                            output.registerPsf(path, finished.fragments, finished.variables);
                        }
                        if (!frames.isEmpty()) {
                            frames.peek().childEnded = true;
                        }
                    }
                    case ':' -> {
                        Frame current = frame(frames, lineNumber);
                        if (current.childEnded) throw error(lineNumber, "Cannot mix child groups and query text");
                        if (!current.sql.toString().isBlank()) {
                            throw error(lineNumber, "Variables must be declared before SQL");
                        }
                        Matcher m = VARIABLE.matcher(args);
                        if (!m.matches()) throw error(lineNumber, "Invalid variable declaration: " + args);
                        PsfVariable variable;
                        try {
                            variable = PsfVariable.parse(m.group(1), m.group(2), m.group(3));
                        } catch (IllegalArgumentException ex) {
                            throw error(lineNumber, ex.getMessage());
                        }
                        if (current.variables.putIfAbsent(variable.name, variable) != null) {
                            throw error(lineNumber, "Duplicate variable: " + variable.name);
                        }
                        current.hasDeclaration = true;
                    }
                    case '*' -> {
                        Frame current = frame(frames, lineNumber);
                        if (current.childEnded) throw error(lineNumber, "Cannot mix child groups and docs");
                        current.hasDeclaration = true;
                    }
                    case '&' -> {
                        Frame current = frame(frames, lineNumber);
                        if (!args.isEmpty()) throw error(lineNumber, "Delimiter must have no arguments");
                        if (current.sql.toString().isBlank()) {
                            throw error(lineNumber, "Delimiter without previous SQL statement");
                        }
                        current.flush(lineNumber);
                    }
                    default -> throw error(lineNumber, "Unknown PSF command: " + action);
                }
            } else if (line.startsWith("--") && frames.isEmpty()) {
                // SQL comments outside a query are ignored.
            } else {
                Frame current = frame(frames, lineNumber);
                if (current.childEnded) throw error(lineNumber, "Cannot mix group and SQL");
                current.sql.append(lines[number]).append('\n');
                current.hasDeclaration = true;
            }
        }
        if (fileDialect == null) throw new IllegalArgumentException("PSF missing dialect declaration");
        if (!frames.isEmpty()) throw new IllegalArgumentException("PSF has unclosed group/query declarations");
        return output;
    }

    private static Frame frame(Deque<Frame> frames, int line) {
        if (frames.isEmpty()) throw error(line, "PSF command/SQL outside a query");
        return frames.peek();
    }

    private static String fullName(Deque<Frame> frames, String local) {
        List<String> names = new ArrayList<>();
        for (var iter = frames.descendingIterator(); iter.hasNext(); ) names.add(iter.next().name);
        names.add(local);
        return String.join(".", names);
    }

    private static IllegalArgumentException error(int line, String message) {
        return new IllegalArgumentException("PSF line " + line + ": " + message);
    }

    private static final class Frame {
        final String name;
        final StringBuilder sql = new StringBuilder();
        final List<String> fragments = new ArrayList<>();
        final Map<String, PsfVariable> variables = new LinkedHashMap<>();
        boolean hasDeclaration;
        boolean childEnded;

        Frame(String name) { this.name = name; }

        boolean isQuery() { return hasDeclaration || !fragments.isEmpty(); }

        void flush(int line) {
            if (sql.toString().isBlank()) throw error(line, "PSF query is missing SQL");
            fragments.add(sql.toString().trim());
            sql.setLength(0);
        }
    }
}
