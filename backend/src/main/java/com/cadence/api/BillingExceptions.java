package com.cadence.api;

/** 032 -- billing domain exceptions (the CsvImportExceptions holder shape). */
public final class BillingExceptions {

    private BillingExceptions() {}

    /** 402 upgrade_required -- a gated action was attempted on a FREE workspace (FR-013). */
    public static class UpgradeRequiredException extends RuntimeException {}

    /**
     * 409 with a typed code -- a claim was refused: invalid_license / wrong_plan /
     * license_inactive / license_already_bound / already_upgraded (FR-006).
     */
    public static class ClaimRejectedException extends RuntimeException {
        private final String code;

        public ClaimRejectedException(String code) {
            super("claim rejected: " + code);
            this.code = code;
        }

        public String code() { return code; }
    }

    /** 503 billing_unavailable -- Freemius unreachable or misconfigured during claim (FR-006). */
    public static class ClaimUnavailableException extends RuntimeException {}
}
