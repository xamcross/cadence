package com.cadence.domain;

import java.time.LocalTime;

/**
 * Embedded value: the workspace working-hours window (wall-clock local time in the workspace zone).
 * {@code end} must be strictly after {@code start} on the same day (overnight windows are rejected in
 * the MVP; DST handling is F12's concern) — validated in {@code WorkspaceConfigService} (FR-005).
 */
public class WorkingHours {

    private LocalTime start;
    private LocalTime end;

    public WorkingHours() {}

    public WorkingHours(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime getStart() { return start; }
    public void setStart(LocalTime start) { this.start = start; }

    public LocalTime getEnd() { return end; }
    public void setEnd(LocalTime end) { this.end = end; }
}
