package com.cadence.security;

import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/**
 * Transparent AES-256-GCM property converter: plaintext in the domain object, ciphertext at rest.
 * Registered per-property in {@link com.cadence.config.MongoPiiConfig} so PII never reaches Mongo
 * in cleartext without any caller having to remember to encrypt (research D12 / SEC-1).
 */
public class PiiStringConverter implements MongoValueConverter<String, String> {

    private final PiiCrypto crypto;

    public PiiStringConverter(PiiCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public String read(String value, MongoConversionContext context) {
        return crypto.decrypt(value);
    }

    @Override
    public String write(String value, MongoConversionContext context) {
        return crypto.encrypt(value);
    }
}
