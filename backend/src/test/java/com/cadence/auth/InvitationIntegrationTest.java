package com.cadence.auth;

import com.cadence.BaseIntegrationTest;
import com.cadence.api.AuthExceptions;
import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.integration.EmailSender;
import com.cadence.service.InvitationService;
import com.cadence.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** US4: invite-only provisioning — single-use, concurrency, no takeover. */
@Import(AuthTestConfig.class)
class InvitationIntegrationTest extends BaseIntegrationTest {

    @Autowired InvitationService invitations;
    @Autowired MemberService members;
    @Autowired PasswordEncoder encoder;
    @MockBean EmailSender emailSender;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Invitation.class);
    }

    private String createAndCaptureToken(String email, Role role) {
        invitations.create("ws1", "admin1", email, role, "127.0.0.1");
        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(emailSender, atLeastOnce()).sendEmail(anyString(), eq("invitation"), cap.capture());
        String link = cap.getValue().get("link");
        return link.substring(link.indexOf("token=") + 6);
    }

    @Test
    void acceptCreatesMemberWithRole_thenLinkIsSingleUse() {
        String token = createAndCaptureToken("invitee@example.com", Role.INTERVIEWER);
        Member created = invitations.accept(token, "set-a-strong-pw", "127.0.0.1");
        assertThat(created.getRole()).isEqualTo(Role.INTERVIEWER);
        assertThat(created.isActive()).isTrue();
        // reuse -> invalid
        assertThatThrownBy(() -> invitations.accept(token, "another-strong-pw", "127.0.0.1"))
            .isInstanceOf(AuthExceptions.InvalidLinkException.class);
    }

    @Test
    void reInviteExistingActiveMember_isRejected_andMemberUnchanged() {
        Member existing = members.create("ws1", "dup@example.com", "Dup", Role.RECRUITER,
            new PasswordCredential(encoder.encode("original-password")), null);
        assertThatThrownBy(() -> invitations.create("ws1", "admin1", "dup@example.com", Role.ADMIN, "127.0.0.1"))
            .isInstanceOf(AuthExceptions.AlreadyMemberException.class);
        Member after = members.findById(existing.getId());
        assertThat(after.getRole()).isEqualTo(Role.RECRUITER); // not escalated to ADMIN
    }

    @Test
    void concurrentAccept_exactlyOneSucceeds() throws Exception {
        String token = createAndCaptureToken("race@example.com", Role.READ_ONLY);
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Callable<Void>> tasks = java.util.Collections.nCopies(n, () -> {
            try {
                invitations.accept(token, "concurrent-strong-pw", "127.0.0.1");
                success.incrementAndGet();
            } catch (RuntimeException e) {
                rejected.incrementAndGet();
            }
            return null;
        });
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(n - 1);
    }

    @Test
    void weakPassword_isRejected_afterValidToken() {
        String token = createAndCaptureToken("weak@example.com", Role.RECRUITER);
        assertThatThrownBy(() -> invitations.accept(token, "short", "127.0.0.1"))
            .isInstanceOf(AuthExceptions.WeakPasswordException.class);
    }
}
