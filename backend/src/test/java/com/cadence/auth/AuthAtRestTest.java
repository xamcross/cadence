package com.cadence.auth;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.service.MemberService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** SC-011: PII is ciphertext at rest, passwords are BCrypt hashes (never plaintext). */
@Import(AuthTestConfig.class)
class AuthAtRestTest extends BaseIntegrationTest {

    @Autowired MemberService memberService;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
    }

    @Test
    void memberEmailAndDisplayNameAreEncrypted_passwordIsBcrypt() {
        String plainEmail = "secret-person@example.com";
        memberService.create("ws1", plainEmail, "Secret Name", Role.RECRUITER,
            new PasswordCredential(encoder.encode("a-strong-password")), null);

        Document raw = mongoTemplate.getCollection("members").find().first();
        assertThat(raw).isNotNull();

        String storedEmail = raw.getString("email");
        assertThat(storedEmail).isNotEqualTo(plainEmail);
        // Ciphertext (base64) must not contain the plaintext bytes.
        String decoded = new String(Base64.getDecoder().decode(storedEmail), StandardCharsets.ISO_8859_1);
        assertThat(decoded).doesNotContain(plainEmail);

        assertThat(raw.getString("displayName")).isNotEqualTo("Secret Name");
        assertThat(raw.getString("emailHash")).isNotBlank();

        Document cred = (Document) raw.get("passwordCredential");
        assertThat(cred.getString("bcryptHash")).startsWith("$2");
        assertThat(cred.getString("bcryptHash")).doesNotContain("a-strong-password");
    }

    @Test
    void emailIsStillReadableThroughTheConverter() {
        memberService.create("ws1", "roundtrip@example.com", "Round Trip", Role.ADMIN, null, null);
        Member loaded = memberService.findActiveByEmail("ws1", "roundtrip@example.com").orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo("roundtrip@example.com");
        assertThat(loaded.getDisplayName()).isEqualTo("Round Trip");
    }
}
