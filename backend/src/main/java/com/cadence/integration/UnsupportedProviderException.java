package com.cadence.integration;

/** An unsupported calendar provider path segment (FR-019) -> 400 unsupported_provider. */
public class UnsupportedProviderException extends RuntimeException {
    public UnsupportedProviderException() {
        super("unsupported calendar provider");
    }
}
