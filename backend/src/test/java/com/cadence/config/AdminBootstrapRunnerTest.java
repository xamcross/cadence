package com.cadence.config;

import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.repository.MemberRepository;
import com.cadence.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdminBootstrapRunner (prod first-Admin seed): seeds exactly one ADMIN when the workspace is empty
 * and bootstrap env is present; is a no-op when members already exist or env is blank (idempotent).
 */
class AdminBootstrapRunnerTest {

    private final MemberRepository members = mock(MemberRepository.class);
    private final MemberService memberService = mock(MemberService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private AdminBootstrapRunner runner(String email, String pw) {
        return new AdminBootstrapRunner(members, memberService, encoder, email, pw, "Admin", "cadence");
    }

    @Test
    void seedsAdminWhenEmptyAndConfigured() {
        when(members.count()).thenReturn(0L);
        when(encoder.encode("s3cret")).thenReturn("$2a$bcrypthash");
        when(memberService.create(any(), any(), any(), any(), any(), any())).thenReturn(new Member());

        runner("admin@example.com", "s3cret").run();

        verify(memberService).create(eq("cadence"), eq("admin@example.com"), eq("Admin"),
            eq(Role.ADMIN), any(PasswordCredential.class), eq(null));
    }

    @Test
    void noOpWhenMembersAlreadyExist() {
        when(members.count()).thenReturn(1L);
        runner("admin@example.com", "s3cret").run();
        verify(memberService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noOpWhenBootstrapEnvBlank() {
        runner("", "").run();
        verifyNoInteractions(members, memberService, encoder);
    }

    @Test
    void doesNotThrowWhenCreateFails() {
        when(members.count()).thenReturn(0L);
        when(encoder.encode(any())).thenReturn("$2a$x");
        when(memberService.create(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("dup key"));
        // Must swallow so a healthy app is not crashed by a seed race.
        runner("admin@example.com", "s3cret").run();
        assertThat(true).isTrue();
    }
}
