package com.cadence.rbac;

import com.cadence.api.RbacExceptions;
import com.cadence.domain.Assignment;
import com.cadence.domain.ResourceType;
import com.cadence.repository.AssignmentRepository;
import com.cadence.repository.MemberRepository;
import com.cadence.service.AssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for AssignmentService scoping primitives (T035, Principle VII unit row). */
class AssignmentServiceTest {

    private AssignmentRepository repo;
    private AssignmentService service;

    @BeforeEach
    void setUp() {
        repo = mock(AssignmentRepository.class);
        MemberRepository members = mock(MemberRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
        service = new AssignmentService(repo, members, clock);
    }

    @Test
    void requireAssigned_throwsWhenNotAssigned() {
        when(repo.existsByWorkspaceIdAndResourceTypeAndResourceIdAndMemberId(
            "ws1", ResourceType.REQUISITION, "req-1", "hm1")).thenReturn(false);
        assertThatThrownBy(() -> service.requireAssigned("ws1", "hm1", ResourceType.REQUISITION, "req-1"))
            .isInstanceOf(RbacExceptions.NotAssignedException.class);
    }

    @Test
    void requireAssigned_passesWhenAssigned() {
        when(repo.existsByWorkspaceIdAndResourceTypeAndResourceIdAndMemberId(
            "ws1", ResourceType.REQUISITION, "req-1", "hm1")).thenReturn(true);
        assertThatCode(() -> service.requireAssigned("ws1", "hm1", ResourceType.REQUISITION, "req-1"))
            .doesNotThrowAnyException();
    }

    @Test
    void getScopedOrNotFound_emptyForMissingOrNotYours_throwsScopedNotFound() {
        // The scoped query returns empty for BOTH "missing" and "exists-but-not-yours" -> one path.
        when(repo.findByWorkspaceIdAndIdAndMemberId("ws1", "a1", "hm1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getScopedOrNotFound("ws1", "hm1", "a1"))
            .isInstanceOf(RbacExceptions.ScopedNotFoundException.class);
    }

    @Test
    void getScopedOrNotFound_returnsWhenOwned() {
        Assignment a = new Assignment();
        a.setId("a1");
        when(repo.findByWorkspaceIdAndIdAndMemberId("ws1", "a1", "hm1")).thenReturn(Optional.of(a));
        assertThatCode(() -> service.getScopedOrNotFound("ws1", "hm1", "a1")).doesNotThrowAnyException();
    }
}
