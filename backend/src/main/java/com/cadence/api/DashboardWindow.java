package com.cadence.api;

import java.time.Duration;
import java.time.Instant;

/**
 * F50 the fixed set of dashboard windows (FR-013/FR-015). The window is a closed enum — never an arbitrary
 * client-supplied range — so a crafted unbounded window cannot be a resource-exhaustion vector, and the
 * aggregations stay index-backed and bounded. Parsed from the {@code window} query param as a String (the F41
 * lesson: binding an enum param directly raises {@code MethodArgumentTypeMismatchException} -> the catch-all 500;
 * parse-as-String -> {@link DashboardExceptions.InvalidRequestException} -> 400).
 */
public enum DashboardWindow {

    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
    LAST_90_DAYS(90);

    private final int days;

    DashboardWindow(int days) {
        this.days = days;
    }

    /** The inclusive-exclusive lower bound: {@code now - {7|30|90} days} (absolute Duration, DST-immune). */
    public Instant windowStart(Instant now) {
        return now.minus(Duration.ofDays(days));
    }

    /**
     * Resolve the {@code window} query param. {@code null} (param absent) defaults to {@code LAST_30_DAYS}
     * (FR-013); any present-but-unrecognised value (incl. blank) throws
     * {@link DashboardExceptions.InvalidRequestException} -> 400.
     */
    public static DashboardWindow parse(String raw) {
        if (raw == null) {
            return LAST_30_DAYS;
        }
        try {
            return DashboardWindow.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new DashboardExceptions.InvalidRequestException();
        }
    }
}
