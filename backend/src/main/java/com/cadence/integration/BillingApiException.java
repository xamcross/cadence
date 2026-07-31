package com.cadence.integration;

/**
 * 032 -- normalized Freemius API failure (the AtsApiException shape). Message carries category and
 * status only -- never the response body, never the bearer.
 */
public class BillingApiException extends RuntimeException {

    private final boolean isTransient;
    private final Integer httpStatus;
    private final String category;

    public BillingApiException(boolean isTransient, Integer httpStatus, String category) {
        super("billing api failure: " + category + " status=" + httpStatus);
        this.isTransient = isTransient;
        this.httpStatus = httpStatus;
        this.category = category;
    }

    public boolean isTransient() { return isTransient; }
    public Integer httpStatus() { return httpStatus; }
    public String category() { return category; }
    public boolean isNotFound() { return httpStatus != null && httpStatus == 404; }
    public boolean isAuth() { return httpStatus != null && (httpStatus == 401 || httpStatus == 403); }
}
