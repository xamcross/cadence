package com.cadence.integration;

/**
 * Reason-aware classification of a Google Calendar API failure (F10, research D8 / plan-review m3).
 * Google overloads {@code 403} for BOTH rate-limiting and auth/scope, so classification inspects the
 * {@code errors[].reason}, not the status alone:
 *
 * <table><caption>truth table</caption>
 *   <tr><td>429 / 5xx / network (null status) / 403 rateLimitExceeded|userRateLimitExceeded</td><td>TRANSIENT</td></tr>
 *   <tr><td>401 / 403 insufficientPermissions|insufficientScope|other-auth</td><td>RECONNECT</td></tr>
 *   <tr><td>other 4xx (e.g. 400, 409)</td><td>FATAL</td></tr>
 * </table>
 */
public final class CalendarApiClassifier {

    private CalendarApiClassifier() {}

    public enum Outcome { TRANSIENT, RECONNECT, FATAL }

    public static Outcome classify(Integer status, String reason) {
        if (status == null) {
            return Outcome.TRANSIENT; // network / connect error
        }
        if (status == 429 || status >= 500) {
            return Outcome.TRANSIENT;
        }
        if (status == 403) {
            if (isQuota403(reason)) {
                return Outcome.TRANSIENT; // throttling, not an auth problem (plan-review m1)
            }
            // insufficientPermissions / insufficientScope / authError / forbidden -> reconnect (B1).
            return Outcome.RECONNECT;
        }
        if (status == 401) {
            return Outcome.RECONNECT;
        }
        return Outcome.FATAL; // other 4xx (incl. 409 handled as idempotent success by the caller)
    }

    /** Google's documented 403 throttling reasons (distinct from auth/scope 403s). */
    private static boolean isQuota403(String reason) {
        return "rateLimitExceeded".equals(reason)
            || "userRateLimitExceeded".equals(reason)
            || "dailyLimitExceeded".equals(reason)
            || "quotaExceeded".equals(reason)
            || "rateLimitExceededUnreg".equals(reason);
    }
}
