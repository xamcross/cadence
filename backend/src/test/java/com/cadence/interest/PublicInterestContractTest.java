package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.Member;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.Role;
import com.cadence.repository.InterestRequestRepository;
import com.cadence.service.InvitationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T009/SC-005/FR-005: the public submit is no-oracle. 202 {@code {"status":"received"}} byte-identical across
 * {active-member, pending-invitation, existing-open, unknown} email; 400 {@code invalid_request}; 429
 * {@code rate_limited}; honeypot -> the same 202 with no row. A structural ArgumentCaptor assertion (the F12
 * multiplicity precedent) guards that {@code notify} fires only on a genuine new insert, not on coalesce.
 */
class PublicInterestContractTest extends InterestItBase {

    // Spy the notification seam to assert it fires only on a new insert (not coalesce). The DB path is real.
    @SpyBean
    com.cadence.service.RecruiterNotificationService notifications;

    // Spy the repository to assert insert(...) is attempted on EVERY submit (the single-code-path / no-oracle
    // guarantee: dedup reaches coalesce via a CAUGHT DuplicateKeyException, never a pre-check existence branch).
    @SpyBean
    InterestRequestRepository spyRepo;

    @Autowired
    InvitationService invitations;

    private static final String BODY = """
        {"name":"Dana Lee","email":"dana@example.com","organization":"Acme","message":"hi","website":""}""";

    /** The byte-identical header baseline captured on the first submit; every later submit must match it (SC-005). */
    private Map<String, String> headerBaseline;

    private void submit(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/public/interest")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isAccepted())
            .andExpect(content().json("{\"status\":\"received\"}"))
            .andExpect(jsonPath("$.status", is("received")))
            .andReturn();
        // SC-005 byte-identical headers: capture the full set on the first call, then assert equality thereafter
        // (Cache-Control and every other response header must be identical across all no-oracle cases).
        Map<String, String> headers = new java.util.TreeMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            headers.put(name, String.join(",", result.getResponse().getHeaders(name)));
        }
        if (headerBaseline == null) {
            headerBaseline = headers;
        } else {
            assertThat(headers).isEqualTo(headerBaseline);
        }
    }

    @Test
    void validSubmit_returns202AndPersistsEncryptedRow_andNotifiesOnce() throws Exception {
        submit(BODY);
        assertThat(interestRepo.findAll()).hasSize(1);
        Mockito.verify(notifications, Mockito.times(1))
            .notify(WS, null, RecruiterNotificationType.INTEREST_REQUEST);
    }

    @Test
    void unknownAndMemberEmail_areByteIdentical202() throws Exception {
        // An active member with the same email — the submit must NOT branch on existence.
        member("dana@example.com", Role.RECRUITER);
        submit(BODY); // member email
        submit("""
            {"name":"X","email":"unknown@example.com","website":""}"""); // unknown email
        // Both returned the identical 202; two distinct rows persist (distinct emails).
        assertThat(interestRepo.findAll()).hasSize(2);
    }

    @Test
    void duplicateOpenEmail_coalesces_oneRow_noSecondNotify() throws Exception {
        submit(BODY);
        submit(BODY); // same email -> DuplicateKeyException -> coalesce
        assertThat(interestRepo.findAll()).hasSize(1);
        // Notify fired ONLY on the first (new insert), not on coalesce.
        Mockito.verify(notifications, Mockito.times(1))
            .notify(WS, null, RecruiterNotificationType.INTEREST_REQUEST);
    }

    @Test
    void invalidBody_returns400_invalidRequest() throws Exception {
        mvc.perform(post("/api/public/interest").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"","email":"not-an-email"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("invalid_request")));
        assertThat(interestRepo.findAll()).isEmpty();
    }

    @Test
    void honeypotTripped_returns202_noRow() throws Exception {
        mvc.perform(post("/api/public/interest").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Bot","email":"bot@example.com","website":"http://spam"}"""))
            .andExpect(status().isAccepted())
            .andExpect(content().json("{\"status\":\"received\"}"));
        assertThat(interestRepo.findAll()).isEmpty();
        Mockito.verifyNoInteractions(notifications);
    }

    @Test
    void perWorkspaceCeilingExceeded_returns429() throws Exception {
        // application-test.yml: max-per-workspace-per-window = 5, max-per-ip-per-window = 3. Use distinct IPs
        // to bypass the per-source limiter so the per-workspace DB ceiling is the gate that trips.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/public/interest").contentType(MediaType.APPLICATION_JSON)
                    .header("CF-Connecting-IP", "10.0.0." + i)
                    .content("{\"name\":\"N\",\"email\":\"e" + i + "@example.com\",\"website\":\"\"}"))
                .andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/public/interest").contentType(MediaType.APPLICATION_JSON)
                .header("CF-Connecting-IP", "10.0.0.99")
                .content("""
                    {"name":"N","email":"over@example.com","website":""}"""))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error", is("rate_limited")));
    }

    @Test
    void existingOpenRequestEmail_isIndistinguishable202() throws Exception {
        // Pre-seed an open request, then submit the same email again — coalesce, still a byte-identical 202.
        InterestRequest seeded = new InterestRequest();
        seeded.setWorkspaceId(WS);
        seeded.setName("Dana Lee");
        seeded.setEmail("dana@example.com");
        seeded.setEmailHash(crypto.emailHash("dana@example.com"));
        seeded.setOpenEmailHash(crypto.emailHash("dana@example.com"));
        seeded.setStatus(InterestRequestStatus.NEW);
        seeded.setSubmittedAt(java.time.Instant.now(clock));
        seeded.setUpdatedAt(java.time.Instant.now(clock));
        mongoTemplate.save(seeded);

        submit(BODY);
        assertThat(mongoTemplate.find(new Query(), InterestRequest.class)).hasSize(1);
    }

    @Test
    void pendingInvitationEmail_isIndistinguishable202() throws Exception {
        // SC-005 4th no-oracle case: a PENDING invitation already exists for the submitted email. The submit must
        // NOT branch on it — byte-identical 202, and a normal interest row is still written (no existence leak).
        Member admin = member("admin@example.com", Role.ADMIN);
        invitations.create(WS, admin.getId(), "dana@example.com", Role.RECRUITER, "1.1.1.1");

        submit(BODY);

        assertThat(interestRepo.findAll()).hasSize(1);
    }

    @Test
    void sameEmailTwice_insertAttemptedBoth_coalesceViaCaughtDuplicate() throws Exception {
        // Structural single-code-path guard (no pre-check existence branch): across two same-email submits the
        // service attempts repo.insert(...) BOTH times — the second hits the unique partial index and coalesces
        // via the CAUGHT DuplicateKeyException, never via an existence read (which would be a no-oracle regression).
        submit(BODY);
        submit(BODY);
        assertThat(interestRepo.findAll()).hasSize(1);
        Mockito.verify(spyRepo, Mockito.times(2)).insert(any(InterestRequest.class));
    }
}
