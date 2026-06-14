package com.cadence.api;

import java.util.Map;

/** F03 workspace-configuration exceptions, mapped to the {error,message} envelope by WorkspaceExceptionHandler. */
public final class WorkspaceExceptions {

    private WorkspaceExceptions() {}

    /** Setup attempted on an already-configured workspace, or the concurrent-upsert loser (FR-006) -> 409. */
    public static class AlreadyConfiguredException extends RuntimeException {}

    /** A settings change attempted before setup completed -> 409. */
    public static class NotConfiguredException extends RuntimeException {}

    /** The data-retention period was not acknowledged at setup (FR-004) -> 400. */
    public static class RetentionNotAcknowledgedException extends RuntimeException {}

    /** Per-field validation failure (FR-005) -> 400 with a non-PII field->message map, nothing persisted. */
    public static class ValidationException extends RuntimeException {
        private final transient Map<String, String> fields;
        public ValidationException(Map<String, String> fields) { this.fields = fields; }
        public Map<String, String> getFields() { return fields; }
    }

    /** Invalid logo upload (size/type/magic-byte/dimensions/undecodable, FR-012/D6) -> 400. */
    public static class InvalidLogoException extends RuntimeException {
        public InvalidLogoException(String message) { super(message); }
    }
}
