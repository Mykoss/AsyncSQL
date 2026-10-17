package io.github.mykoss.asyncsql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled single or multi-statement query using one JDBC connection per invocation. */
final class SqlQuery {
    enum Mode { GENERIC, CHANGE, INSERT, SELECT, SELECT_INFO }
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
        List<Object> parts = executeMulti(connection, parameters, List.of(mode));
        return parts.get(parts.size() - 1);
    }

    /**
     * Executes PSF fragments in order on the same connection. If a single mode
     * is provided, all but the final fragment run as GENERIC.
     */
    List<Object> executeMulti(Connection connection, Map<String, ?> parameters, List<Mode> modes)
            throws SQLException {
        validate(parameters);
        if (modes.size() != 1 && modes.size() != statements.size()) {
            throw new IllegalArgumentException("Provide one mode or one mode per SQL fragment");
        }
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            Mode mode = modes.size() == 1
                    ? (i == statements.size() - 1 ? modes.get(0) : Mode.GENERIC)
                    : modes.get(i);
            NamedStatement.Plan plan = statements.get(i).plan(parameters, variables);
            try (PreparedStatement statement = connection.prepareStatement(plan.sql(),
                    mode == Mode.INSERT ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS)) {
                for (int pos = 0; pos < plan.arguments().size(); pos++) {
                    statement.setObject(pos + 1, plan.arguments().get(pos));
                }
                result.add(executeOne(statement, mode));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void validate(Map<String, ?> parameters) {
        for (PsfVariable var : variables.values()) {
            if (!var.optional && !parameters.containsKey(var.name)) {
                throw new IllegalArgumentException("Missing required PSF parameter: " + var.name);
            }
        }
    }

    private static Object executeOne(PreparedStatement statement, Mode mode) throws SQLException {
        return switch (mode) {
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
            case SELECT, SELECT_INFO -> {
                List<SqlRow> rows = new ArrayList<>();
                List<SqlColumnInfo> columns = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData metadata = rs.getMetaData();
                    for (int col = 1; col <= metadata.getColumnCount(); col++) {
                        columns.add(new SqlColumnInfo(metadata.getColumnLabel(col),
                                columnType(metadata.getColumnType(col))));
                    }
                    while (rs.next()) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (int col = 1; col <= metadata.getColumnCount(); col++) {
                            values.put(metadata.getColumnLabel(col), rs.getObject(col));
                        }
                        rows.add(new SqlRow(values));
                    }
                }
                yield mode == Mode.SELECT_INFO
                        ? new SqlSelectResult(List.copyOf(columns), List.copyOf(rows))
                        : List.copyOf(rows);
            }
        };
    }

    private static String columnType(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "int";
            case Types.FLOAT, Types.DOUBLE, Types.REAL, Types.DECIMAL, Types.NUMERIC -> "float";
            case Types.BOOLEAN, Types.BIT -> "bool";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.DATE, Types.TIME,
                    Types.TIME_WITH_TIMEZONE -> "timestamp";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> "string";
            case Types.NULL -> "null";
            default -> "unknown";
        };
    }

    int fragments() { return statements.size(); }
}
