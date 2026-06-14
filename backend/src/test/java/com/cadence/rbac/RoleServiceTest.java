package com.cadence.rbac;

import com.cadence.api.RbacExceptions;
import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
import com.cadence.domain.Role;
import com.cadence.repository.MemberRepository;
import com.cadence.service.AuthAuditService;
import com.cadence.service.RoleService;
import com.cadence.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RoleService branch logic (T020, Principle VII unit row): self-elevation guard,
 * not-found, same-role no-op, and the last-Admin flip -> recount -> rollback decision (mocked Mongo).
 */
class RoleServiceTest {

    private MongoTemplate mongo;
    private MemberRepository members;
    private SessionService sessions;
    private AuthAuditService audit;
    private RoleService service;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        members = mock(MemberRepository.class);
        sessions = mock(SessionService.class);
        audit = mock(AuthAuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
        service = new RoleService(mongo, members, sessions, audit, clock);
    }

    private Member member(String id, String ws, Role role) {
        Member m = new Member();
        m.setId(id);
        m.setWorkspaceId(ws);
        m.setRole(role);
        m.setStatus(MemberStatus.ACTIVE);
        return m;
    }

    @Test
    void selfElevation_isRejected() {
        Member self = member("m1", "ws1", Role.RECRUITER);
        when(members.findById("m1")).thenReturn(Optional.of(self));
        // actor == target, RECRUITER -> ADMIN is an elevation (defensive depth; endpoint is ADMIN-only).
        assertThatThrownBy(() -> service.changeRole("ws1", "m1", "m1", Role.ADMIN))
            .isInstanceOf(RbacExceptions.SelfElevationException.class);
        verify(audit, never()).roleChanged(any(), any(), any(), any(), any());
    }

    @Test
    void unknownTarget_isNotFound() {
        when(members.findById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeRole("ws1", "admin", "ghost", Role.READ_ONLY))
            .isInstanceOf(RbacExceptions.ScopedNotFoundException.class);
    }

    @Test
    void crossWorkspaceTarget_isNotFound() {
        Member other = member("m2", "ws2", Role.RECRUITER);
        when(members.findById("m2")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.changeRole("ws1", "admin", "m2", Role.READ_ONLY))
            .isInstanceOf(RbacExceptions.ScopedNotFoundException.class);
    }

    @Test
    void sameRole_isNoOp_noAudit() {
        Member m = member("m3", "ws1", Role.RECRUITER);
        when(members.findById("m3")).thenReturn(Optional.of(m));
        service.changeRole("ws1", "admin", "m3", Role.RECRUITER);
        verify(audit, never()).roleChanged(any(), any(), any(), any(), any());
        verify(members, never()).save(any());
    }

    @Test
    void nonAdminRoleChange_savesAndAudits() {
        Member m = member("m4", "ws1", Role.RECRUITER);
        when(members.findById("m4")).thenReturn(Optional.of(m));
        service.changeRole("ws1", "admin", "m4", Role.READ_ONLY);
        verify(members).save(m);
        verify(audit).roleChanged("ws1", "admin", "m4", Role.RECRUITER, Role.READ_ONLY);
        assertThat(m.getRole()).isEqualTo(Role.READ_ONLY);
    }

    @Test
    void adminDowngrade_succeedsWhenAnotherAdminRemains() {
        Member admin = member("a1", "ws1", Role.ADMIN);
        when(members.findById("a1")).thenReturn(Optional.of(admin));
        // flip succeeds (returns the flipped doc) and recount shows another admin remains
        when(mongo.findAndModify(any(Query.class), any(), any(), eq(Member.class))).thenReturn(admin);
        when(mongo.count(any(Query.class), eq(Member.class))).thenReturn(1L);
        service.changeRole("ws1", "actor", "a1", Role.RECRUITER);
        verify(audit).roleChanged("ws1", "actor", "a1", Role.ADMIN, Role.RECRUITER);
    }

    @Test
    void adminDowngrade_lastAdmin_rollsBackAndThrows() {
        Member admin = member("a1", "ws1", Role.ADMIN);
        when(members.findById("a1")).thenReturn(Optional.of(admin));
        when(mongo.findAndModify(any(Query.class), any(), any(), eq(Member.class))).thenReturn(admin);
        when(mongo.count(any(Query.class), eq(Member.class))).thenReturn(0L); // none remain after flip
        assertThatThrownBy(() -> service.changeRole("ws1", "actor", "a1", Role.RECRUITER))
            .isInstanceOf(RbacExceptions.LastAdminException.class);
        verify(audit, never()).roleChanged(any(), any(), any(), any(), any());
    }
}
