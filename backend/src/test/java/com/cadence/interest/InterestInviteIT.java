package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.service.InterestRequestService.InviteResult;
import com.cadence.service.InterestRequestService.SubmitCommand;
import com.cadence.service.InvitationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T023: invite from a NEW/REVIEWED request issues a real invitation, the request -> INVITED with invitationId set
 * and openEmailHash unset; invite a request whose email is an active member -> terminal, no second invitation, no
 * 500, an {@code alreadyMember} outcome.
 */
class InterestInviteIT extends InterestItBase {

    // Spy the invitation seam so a single invite can be forced to throw a transient failure, then restored.
    @SpyBean
    InvitationService invitationService;

    @Test
    void inviteFromNew_issuesInvitation_requestInvited_openHashUnset() {
        configuredWorkspace(365);
        interestService.submit(new SubmitCommand("Dana", "dana@example.com", null, null, null, null), "1.1.1.1");
        InterestRequest req = interestRepo.findAll().get(0);
        Member admin = member("admin@example.com", Role.ADMIN);

        InviteResult result = interestService.invite(WS, req.getId(), Role.RECRUITER, admin.getId(), "1.1.1.1");

        assertThat(result.alreadyMember()).isFalse();
        assertThat(result.invitationId()).isNotBlank();
        assertThat(mongoTemplate.findAll(Invitation.class)).hasSize(1);

        InterestRequest after = interestRepo.findByWorkspaceIdAndId(WS, req.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InterestRequestStatus.INVITED);
        assertThat(after.getInvitationId()).isEqualTo(result.invitationId());
        assertThat(after.getOpenEmailHash()).isNull(); // unset -> falls out of the dedup index
    }

    @Test
    void inviteWhenEmailIsActiveMember_terminalAlreadyMember_noSecondInvitation_no500() {
        configuredWorkspace(365);
        // Pre-create an active member with the SAME email the request will carry.
        member("dupe@example.com", Role.RECRUITER);
        interestService.submit(new SubmitCommand("Dupe", "dupe@example.com", null, null, null, null), "1.1.1.1");
        InterestRequest req = interestRepo.findAll().get(0);
        Member admin = member("admin@example.com", Role.ADMIN);

        InviteResult result = interestService.invite(WS, req.getId(), Role.RECRUITER, admin.getId(), "1.1.1.1");

        assertThat(result.alreadyMember()).isTrue();
        assertThat(result.invitationId()).isNull();
        assertThat(mongoTemplate.findAll(Invitation.class)).isEmpty(); // NO second access path
        InterestRequest after = interestRepo.findByWorkspaceIdAndId(WS, req.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InterestRequestStatus.INVITED); // terminal (resolved)
        assertThat(after.getOpenEmailHash()).isNull();
    }

    @Test
    void inviteTransientCreateFailure_revertsClaim_rowReactionable() {
        configuredWorkspace(365);
        interestService.submit(new SubmitCommand("Dana", "dana@example.com", null, null, null, null), "1.1.1.1");
        InterestRequest req = interestRepo.findAll().get(0);
        String expectedHash = crypto.emailHash("dana@example.com");
        Member admin = member("admin@example.com", Role.ADMIN);

        // Force a transient (non-AlreadyMember) failure from the create seam for the FIRST invite only.
        Mockito.doThrow(new RuntimeException("transient mongo/email failure"))
            .when(invitationService).create(WS, admin.getId(), "dana@example.com", Role.RECRUITER, "1.1.1.1");

        assertThatThrownBy(() ->
            interestService.invite(WS, req.getId(), Role.RECRUITER, admin.getId(), "1.1.1.1"))
            .isInstanceOf(RuntimeException.class);

        // The claim was reverted: NOT left terminal-uninvited — back to the prior open status with the hash restored.
        InterestRequest reverted = interestRepo.findByWorkspaceIdAndId(WS, req.getId()).orElseThrow();
        assertThat(reverted.getStatus()).isEqualTo(InterestRequestStatus.NEW);
        assertThat(reverted.getInvitationId()).isNull();
        assertThat(reverted.getOpenEmailHash()).isEqualTo(expectedHash);
        assertThat(mongoTemplate.findAll(Invitation.class)).isEmpty();

        // Restore the real seam — a subsequent invite must now succeed (the row is re-actionable).
        Mockito.reset(invitationService);
        InviteResult retry = interestService.invite(WS, req.getId(), Role.RECRUITER, admin.getId(), "1.1.1.1");
        assertThat(retry.alreadyMember()).isFalse();
        assertThat(retry.invitationId()).isNotBlank();
        assertThat(mongoTemplate.findAll(Invitation.class)).hasSize(1);
        InterestRequest after = interestRepo.findByWorkspaceIdAndId(WS, req.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InterestRequestStatus.INVITED);
        assertThat(after.getInvitationId()).isEqualTo(retry.invitationId());
        assertThat(after.getOpenEmailHash()).isNull();
    }
}
