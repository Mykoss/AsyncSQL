package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NamedStatementTest {
    @Test void handlesQuotesCommentsAndRepeatedParameters() {
        NamedStatement query = new NamedStatement("""
            SELECT ':fake', " :fake ", `:fake`, :id, :id -- :comment
            /* :ignored */ WHERE value = ':literal' AND x = :x
            """);
        assertEquals(List.of("id", "id", "x"), query.keys());
        assertTrue(query.sql().contains("?, ? -- :comment"));
        assertTrue(query.sql().contains("/* :ignored */"));
    }
}
