package io.github.mykoss.asyncsql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled single or multi-statement query using one JDBC connection per invocation. */
final class SqlQuery {
    enum Mode { GENERIC, CHANGE, INSERT, SELECT }
    private final List<NamedStatement> statements;
    private final Map<String, PsfVariable> variables;

    SqlQuery(List<String> statements, Map<String, PsfVariable> variables) {
        this(statements, variables, false);
    }

    SqlQuery(List<String> statements, Map<String, PsfVariable> variables, boolean isPsf) {
        if (statements.isEmpty()) throw new IllegalArgumentException("A query needs SQL");
        this.statements = statements.stream().map(NamedStatement::new).toList();
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        if (isPsf) {
            for (PsfVariable var : variables.values()) {
                boolean used = this.statements.stream().anyMatch(stmt -> stmt.keys().contains(var.name));
                if (!used) throw new IllegalArgumentException("Declared PSF variable is not used: " + var.name);
            }
            for (NamedStatement stmt : this.statements) {
                for (String key : stmt.keys()) {
                    if (!variables.containsKey(key)) {
                        throw new IllegalArgumentException("PSF variable is not declared: " + key);
                    }
                }
            }
        }
    }

    Object execute(Connection connection, Map<String, ?> parameters, Mode mode) throws SQLException {
        // Check required params even if a variable is not referenced in the last SQL fragment.
        for (PsfVariable var : variables.values()) {
            if (!var.optional && !parameters.containsKey(var.name)) {
                throw new IllegalArgumentException("Missing required PSF parameter: " + var.name);
            }
        }
        Object answer = null;
        for (int i = 0; i < statements.size(); i++) {
            boolean last = i == statements.size() - 1;
            Mode current = last ? mode : Mode.GENERIC;
            NamedStatement.Plan plan = statements.get(i).plan(parameters, variables);
            try (PreparedStatement statement = connection.prepareStatement(plan.sql(),
                    current == Mode.INSERT ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS)) {
                for (int index = 0; index < plan.arguments().size(); index++) {
                    statement.setObject(index + 1, plan.arguments().get(index));
                }
                answer = switch (current) {
                    case GENERIC -> {
                        statement.execute();
                        yield null;
                    }
                    case CHANGE -> statement.executeUpdate();
                    case INSERT -> {
                        int changed = statement.executeUpdate();
                        long id = -1;
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (keys != null && keys.next()) id = keys.getLong(1);
                        } catch (SQLFeatureNotSupportedException ignored) {
                            // Driver doesn't support generated keys.
                        }
                        yield new InsertResult(id, changed);
                    }
                    case SELECT -> {
                        List<SqlRow> rows = new ArrayList<>();
                        try (ResultSet rs = statement.executeQuery()) {
                            ResultSetMetaData metadata = rs.getMetaData();
                            while (rs.next()) {
                                Map<String, Object> columns = new LinkedHashMap<>();
                                for (int col = 1; col <= metadata.getColumnCount(); col++) {
                                    columns.put(metadata.getColumnLabel(col), rs.getObject(col));
                                }
                                rows.add(new SqlRow(columns));
                            }
                        }
                        yield List.copyOf(rows);
                    }
                };
            }
        }
        return answer;
    }

    int fragments() { return statements.size(); }
}
