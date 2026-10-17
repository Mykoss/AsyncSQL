package io.github.mykoss.asyncsql;

/** Success marker for GENERIC commands, matching libasynql's result family. */
public record SqlGenericResult() implements SqlResult {}
