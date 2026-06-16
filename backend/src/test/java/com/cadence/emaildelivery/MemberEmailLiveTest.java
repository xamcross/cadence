package com.cadence.emaildelivery;

import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.integration.OutboundEmail;
import com.cadence.service.InvitationService;
import com.cadence.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T022 (US1, §II) — the F01 member-invitation + password-reset flows now genuinely transmit through the
 * widened EmailSender -> RecordingMailTransport (previously a no-op). Member mail is NOT consent-gated
 * (members aren't candidates) — these sends succeed with no candidate/ContactPermissionGate involvement.
 */
class MemberEmailLiveTest extends EmailDeliveryItBase {

    @Autowired InvitationService invitations;
    @Autowired PasswordResetService resets;
    @Autowired PasswordEncoder encoder;

    @Test
    void invitation_transmitsThroughTransport() {
        Member admin = member("admin@x.com", Role.ADMIN);
        invitations.create(WS, admin.getId(), "newhire@x.com", Role.RECRUITER, null);

        assertThat(recordingTransport.totalCalls()).isEqualTo(1);
        OutboundEmail sent = recordingTransport.messages().get(0);
        assertThat(sent.toAddress()).isEqualTo("newhire@x.com");
        assertThat(sent.subject()).isNotBlank();
    }

    @Test
    void passwordReset_transmitsThroughTransport() {
        memberService.create(WS, "reset@x.com", "Reset User", Role.RECRUITER,
            new PasswordCredential(encoder.encode("old-password-123")), null);

        resets.request(WS, "reset@x.com", null);

        assertThat(recordingTransport.totalCalls()).isEqualTo(1);
        assertThat(recordingTransport.messages().get(0).toAddress()).isEqualTo("reset@x.com");
    }

    @Test
    void passwordReset_unknownEmail_noTransmit_enumerationSafe() {
        resets.request(WS, "nobody@x.com", null);
        assertThat(recordingTransport.totalCalls()).isZero();
    }
}
