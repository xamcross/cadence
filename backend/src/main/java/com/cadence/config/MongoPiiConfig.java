package com.cadence.config;

import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.security.PiiCrypto;
import com.cadence.security.PiiStringConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

/**
 * Registers the per-property PII converter so {@code Member.email}, {@code Member.displayName} and
 * {@code Invitation.email} are encrypted at rest automatically (research D12). Using programmatic
 * property conversions avoids spreading crypto calls across the service layer.
 */
@Configuration
public class MongoPiiConfig {

    @Bean
    public MongoCustomConversions mongoCustomConversions(PiiCrypto crypto) {
        PiiStringConverter converter = new PiiStringConverter(crypto);
        return MongoCustomConversions.create(adapter ->
            adapter.configurePropertyConversions(registrar -> {
                registrar.registerConverter(Member.class, "email", converter);
                registrar.registerConverter(Member.class, "displayName", converter);
                registrar.registerConverter(Invitation.class, "email", converter);
                // F03: encrypt the email-provider credential at rest (research D2). This is the
                // encryption-at-rest control only; never-return is a SEPARATE control (the field is
                // @JsonIgnore + on no response DTO). Reuse the SAME converter instance; one bean only.
                registrar.registerConverter(WorkspaceConfig.class, "emailProviderCredential", converter);
            }));
    }
}
