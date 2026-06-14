package com.cadence.service;

import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.domain.SsoIdentity;
import com.cadence.repository.MemberRepository;
import com.cadence.security.PiiCrypto;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Gateway for member identity. Computes the keyed {@code emailHash} for the unique index/lookup
 * (research D12); email/displayName ciphertext is handled transparently by the registered property
 * converter, so callers work with plaintext.
 */
@Service
public class MemberService {

    private final MemberRepository members;
    private final PiiCrypto crypto;
    private final Clock clock;

    public MemberService(MemberRepository members, PiiCrypto crypto, Clock clock) {
        this.members = members;
        this.crypto = crypto;
        this.clock = clock;
    }

    public Optional<Member> findActiveByEmail(String workspaceId, String email) {
        return members.findByWorkspaceIdAndEmailHash(workspaceId, crypto.emailHash(email))
            .filter(Member::isActive);
    }

    public Optional<Member> findByEmail(String workspaceId, String email) {
        return members.findByWorkspaceIdAndEmailHash(workspaceId, crypto.emailHash(email));
    }

    public Optional<Member> findActiveBySso(String provider, String subject) {
        return members.findBySsoProviderAndSsoSubject(provider, subject).filter(Member::isActive);
    }

    public boolean existsByEmail(String workspaceId, String email) {
        return members.existsByWorkspaceIdAndEmailHash(workspaceId, crypto.emailHash(email));
    }

    public Member findById(String id) {
        return members.findById(id).orElseThrow(() -> new IllegalArgumentException("member not found"));
    }

    public Optional<Member> findByIdOptional(String id) {
        return members.findById(id);
    }

    /** Create a member during invitation acceptance (FR-018). */
    public Member create(String workspaceId, String email, String displayName, Role role,
                         PasswordCredential password, SsoIdentity sso) {
        Instant now = Instant.now(clock);
        Member m = new Member();
        m.setWorkspaceId(workspaceId);
        m.setEmail(email);
        m.setEmailHash(crypto.emailHash(email));
        m.setDisplayName(displayName);
        m.setRole(role);
        m.setStatus(MemberStatus.ACTIVE);
        m.setPasswordCredential(password);
        applySso(m, sso);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return members.save(m);
    }

    public Member save(Member member) {
        member.setUpdatedAt(Instant.now(clock));
        if (member.getEmail() != null && member.getEmailHash() == null) {
            member.setEmailHash(crypto.emailHash(member.getEmail()));
        }
        return members.save(member);
    }

    /** Set the SSO identity plus the denormalised index fields together. */
    public void applySso(Member m, SsoIdentity sso) {
        m.setSsoIdentity(sso);
        m.setSsoProvider(sso == null ? null : sso.getProvider());
        m.setSsoSubject(sso == null ? null : sso.getSubject());
    }
}
