package com.cadence.api;

/**
 * F51 Pipeline View domain exceptions, mapped to a value-free envelope by {@link PipelineExceptionHandler}.
 * Distinct types (NOT reused from other packages) so there is no FQN collision and the handler binding is exact
 * (the F50 lesson). {@code ScopedNotFoundException} (no-oracle 404) is reused from {@link RbacExceptions}.
 */
public final class PipelineExceptions {

    private PipelineExceptions() {}

    /** Bad/blank-but-present query param, bad enum, empty selection, missing required bulk field -> 400. */
    public static class InvalidRequestException extends RuntimeException {}

    /** Bulk selection exceeds {@code cadence.pipeline.bulk-max} -> 400 (checked before any candidate is touched). */
    public static class SelectionTooLargeException extends RuntimeException {}
}
