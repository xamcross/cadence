package com.cadence.api;

import java.util.Map;

/** F21 email-template exceptions, mapped to the shared {error,message} envelope by the handler. */
public final class EmailTemplateExceptions {

    private EmailTemplateExceptions() {}

    /**
     * Per-field validation failure (FR-004/FR-020) -> 400. The field->message map is VALUE-FREE
     * (field + rule, never the submitted subject/body/token text) so PII or recruiter content cannot leak (D12).
     */
    public static class InvalidTemplateException extends RuntimeException {
        private final transient Map<String, String> fields;
        public InvalidTemplateException(Map<String, String> fields) { this.fields = fields; }
        public Map<String, String> getFields() { return fields; }
    }

    /** A Recruiter tried to edit/tone/variant/reset a LOCKED template (FR-010) -> 403. */
    public static class TemplateLockedException extends RuntimeException {}

    /** A stale optimistic-concurrency write (version mismatch or concurrent first-edit) (FR-011) -> 409. */
    public static class StaleTemplateException extends RuntimeException {}
}
