package com.fitbook.config;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.SQLException;

/**
 * Spring has no built-in error-code table for SQLite, so every failure would surface as a
 * generic UncategorizedSQLException. This maps the two cases the booking logic cares about:
 * lock contention becomes a retryable CannotAcquireLockException, and constraint violations
 * (the unique-index double-booking backstop) become DuplicateKeyException /
 * DataIntegrityViolationException. Anything else returns null so Spring falls back to its default.
 */
public class SqliteExceptionTranslator implements SQLExceptionTranslator {

    @Override
    public DataAccessException translate(String task, String sql, SQLException ex) {
        if (ex instanceof SQLiteException sqlite) {
            SQLiteErrorCode code = sqlite.getResultCode();
            String name = code.name();
            if (name.startsWith("SQLITE_BUSY") || name.startsWith("SQLITE_LOCKED")) {
                return new CannotAcquireLockException(task + ": database is busy", ex);
            }
            if (code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE || code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                return new DuplicateKeyException(task + ": unique constraint violated", ex);
            }
            if (name.startsWith("SQLITE_CONSTRAINT")) {
                return new DataIntegrityViolationException(task + ": constraint violated", ex);
            }
        }
        return null;
    }
}
