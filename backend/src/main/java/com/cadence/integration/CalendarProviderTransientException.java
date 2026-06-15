package com.cadence.integration;

/**
 * A transient provider failure (429/5xx/network) during refresh, after bounded retry was exhausted
 * (FR-016). The connection is left CONNECTED and unchanged — the caller may retry later.
 */
public class CalendarProviderTransientException extends RuntimeException {
    public CalendarProviderTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
