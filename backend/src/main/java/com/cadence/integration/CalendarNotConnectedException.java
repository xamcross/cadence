package com.cadence.integration;

/** No calendar connection exists for the (member, provider) — thrown by validAccessToken (contracts §7). */
public class CalendarNotConnectedException extends RuntimeException {
    public CalendarNotConnectedException() {
        super("calendar not connected");
    }
}
