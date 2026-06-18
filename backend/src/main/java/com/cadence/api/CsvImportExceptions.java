package com.cadence.api;

/**
 * F42 import exceptions, mapped to value-free envelopes by {@link CsvImportExceptionHandler}. The no-oracle
 * 404 reuses {@link RbacExceptions.ScopedNotFoundException} (inherits the proven mapping).
 */
public final class CsvImportExceptions {

    private CsvImportExceptions() {}

    /** 400 invalid_import — missing/empty file, or a malformed resolve request. */
    public static class InvalidImportException extends RuntimeException {}

    /** 409 invalid_state — resolve attempted on a job not AWAITING_DUPLICATE_DECISION. */
    public static class InvalidStateException extends RuntimeException {}

    /** 429 rate_limited — per-IP advisory limit exceeded on upload. */
    public static class RateLimitedException extends RuntimeException {}
}
