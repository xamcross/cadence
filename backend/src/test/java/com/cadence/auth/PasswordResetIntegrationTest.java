package com.cadence.auth;

import com.cadence.BaseIntegrationTest;
import com.cadence.api.AuthExceptions;
import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.PasswordResetToken;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.integration.EmailSender;
import com.cadence.repository.SessionRepository;
import com.cadence.service.MemberService;
import com.cadence.service.PasswordResetService;
import com.cadence.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** US3 reset: enumeration-safe request, rotate + revoke on confirm, uniform invalid link. */
@Import(AuthTestConfig.class)
class PasswordResetIntegrationTest extends BaseIntegrationTest {

    @Autowired PasswordResetService reset;
    @Autowired MemberService members;
    @Autowired SessionService sessions;
    @Autowired SessionRepository sessionRepo;
    @Autowired PasswordEncoder encoder;
    @MockBean EmailSender emailSender;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), PasswordResetToken.class);
    }

    private Member fallbackMember() {
        return members.create("ws1", "reset@example.com", "Reset User", Role.RECRUITER,
            new PasswordCredential(encoder.encode("old-password-123")), null);
    }

    private String captureResetToken() {
        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(emailSender, atLeastOnce()).sendEmail(anyString(), eq("password-reset"), cap.capture());
        String link = cap.getValue().get("link");
        return link.substring(link.indexOf("token=") + 6);
    }

    @Test
    void requestForUnknownEmail_sendsNothing_butDoesNotThrow() {
        reset.request("ws1", "nobody@example.com", "127.0.0.1");
        verify(emailSender, never()).sendEmail(anyString(), eq("password-reset"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmRotatesCredentialAndRevokesSessions() {
        Member m = fallbackMember();
        SessionService.Issued issued = sessions.issue(m); // an active session that must be revoked
        reset.request("ws1", "reset@example.com", "127.0.0.1");
        String token = captureResetToken();

        reset.confirm(token, "brand-new-password", "127.0.0.1");

        Member after = members.findById(m.getId());
        assertThat(encoder.matches("brand-new-password", after.getPasswordCredential().getBcryptHash())).isTrue();
        assertThat(encoder.matches("old-password-123", after.getPasswordCredential().getBcryptHash())).isFalse();
        assertThat(sessionRepo.findById(issued.session().getId()).orElseThrow().isRevoked()).isTrue();
    }

    @Test
    void weakPassword_isRejectedRegardlessOfToken_noOracle() {
        // Password policy is checked first, so a weak password yields weak_password whether or not
        // the token is valid — the response cannot be used to probe token validity (SEC-6).
        assertThatThrownBy(() -> reset.confirm("forged-token", "x", "127.0.0.1"))
            .isInstanceOf(AuthExceptions.WeakPasswordException.class);
    }

    @Test
    void forgedTokenWithStrongPassword_returnsInvalidLink() {
        assertThatThrownBy(() -> reset.confirm("forged-token", "a-strong-password", "127.0.0.1"))
            .isInstanceOf(AuthExceptions.InvalidLinkException.class);
    }

    @Test
    void usedToken_cannotBeReused() {
        fallbackMember();
        reset.request("ws1", "reset@example.com", "127.0.0.1");
        String token = captureResetToken();
        reset.confirm(token, "first-new-password", "127.0.0.1");
        assertThatThrownBy(() -> reset.confirm(token, "second-new-password", "127.0.0.1"))
            .isInstanceOf(AuthExceptions.InvalidLinkException.class);
    }
}
