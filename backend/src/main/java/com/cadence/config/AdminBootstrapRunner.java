package com.cadence.config;

import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.repository.MemberRepository;
import com.cadence.service.MemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * First-Admin bootstrap (prod only). The product has no self-registration and SSO does not
 * auto-provision, so a fresh deployment has zero members and nobody can sign in (invitations require
 * an existing Admin). This runner seeds ONE Admin from env vars IFF the {@code members} collection is
 * empty — making it idempotent and a no-op on every subsequent boot and on any non-empty workspace.
 *
 * It uses {@link MemberService#create} so the email ciphertext, {@code emailHash} HMAC, and BCrypt
 * password hash are produced by exactly the same crypto the login path verifies against.
 *
 * Env (Fly secrets): CADENCE_BOOTSTRAP_ADMIN_EMAIL, CADENCE_BOOTSTRAP_ADMIN_PASSWORD,
 * CADENCE_BOOTSTRAP_ADMIN_NAME (default "Admin"), CADENCE_BOOTSTRAP_WORKSPACE_ID (default "cadence").
 * The admin then signs in at /login with that workspace id + email + password and runs first-run setup.
 */
@Component
@Profile("prod")
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final MemberRepository members;
    private final MemberService memberService;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;
    private final String workspaceId;

    public AdminBootstrapRunner(MemberRepository members, MemberService memberService, PasswordEncoder encoder,
                                @Value("${CADENCE_BOOTSTRAP_ADMIN_EMAIL:}") String adminEmail,
                                @Value("${CADENCE_BOOTSTRAP_ADMIN_PASSWORD:}") String adminPassword,
                                @Value("${CADENCE_BOOTSTRAP_ADMIN_NAME:Admin}") String adminName,
                                @Value("${CADENCE_BOOTSTRAP_WORKSPACE_ID:cadence}") String workspaceId) {
        this.members = members;
        this.memberService = memberService;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
        this.workspaceId = workspaceId;
    }

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            return; // bootstrap not requested
        }
        long existing = members.count();
        if (existing > 0) {
            log.info("AdminBootstrap: {} member(s) already present — skipping seed.", existing);
            return;
        }
        try {
            Member m = memberService.create(
                workspaceId,
                adminEmail,
                adminName,
                Role.ADMIN,
                new PasswordCredential(encoder.encode(adminPassword)),
                null);
            // No PII in logs (Principle VIII): only the internal id + workspace id.
            log.info("AdminBootstrap: seeded first ADMIN (memberId={}, workspaceId={}).", m.getId(), workspaceId);
        } catch (RuntimeException e) {
            // Do not crash a healthy app if the seed races/fails; the unique emailHash index also guards.
            log.error("AdminBootstrap: failed to seed first ADMIN ({}).", e.getClass().getSimpleName());
        }
    }
}
