package com.cadence.integration;

/**
 * Classifies a Greenhouse Harvest API failure (F40, mirrors {@link CalendarApiClassifier#classifyGraph}).
 * Greenhouse throttling is {@code 429} (with {@code Retry-After}), so a {@code 401}/{@code 403} is
 * unambiguously an auth/credential problem -> AUTH (flip the connection to NEEDS_REAUTH, no retry). Only
 * {@code error.code}-free status is needed to decide.
 *
 * <table><caption>truth table</caption>
 *   <tr><td>429 / 5xx / network (null status)</td><td>TRANSIENT</td></tr>
 *   <tr><td>401 / 403</td><td>AUTH</td></tr>
 *   <tr><td>other 4xx (400 / 404 / 422)</td><td>FATAL</td></tr>
 * </table>
 */
public final class AtsApiClassifier {

    private AtsApiClassifier() {}

    public enum Outcome { TRANSIENT, AUTH, FATAL }

    public static Outcome classify(Integer status) {
        if (status == null) {
            return Outcome.TRANSIENT; // network / connect error
        }
        if (status == 429 || status >= 500) {
            return Outcome.TRANSIENT;
        }
        if (status == 401 || status == 403) {
            return Outcome.AUTH; // throttling is 429, so a 401/403 is always a credential problem
        }
        return Outcome.FATAL; // other 4xx
    }
}
