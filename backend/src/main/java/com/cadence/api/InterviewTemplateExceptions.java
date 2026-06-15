package com.cadence.api;

import java.util.Map;

/** F12 interview-template exceptions, mapped to the shared {error,message} envelope by the handler. */
public final class InterviewTemplateExceptions {

    private InterviewTemplateExceptions() {}

    /**
     * Per-field validation failure (FR-002/FR-024) -> 400. The field->message map is VALUE-FREE
     * (field + rule, never the submitted value) so a PII-laden bad value cannot leak (D10).
     */
    public static class InvalidTemplateException extends RuntimeException {
        private final transient Map<String, String> fields;
        public InvalidTemplateException(Map<String, String> fields) { this.fields = fields; }
        public Map<String, String> getFields() { return fields; }
    }

    /** Computing slots against a retired template (FR-007) -> 409 (distinguishable, not an empty list). */
    public static class TemplateRetiredException extends RuntimeException {}

    /** Compute requested before the workspace has working hours / a time zone (no override, F03 not set) -> 409. */
    public static class WorkspaceNotConfiguredException extends RuntimeException {}
}
