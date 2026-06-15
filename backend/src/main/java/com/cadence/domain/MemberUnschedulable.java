package com.cadence.domain;

/** A required member who could not be scheduled, with a reason distinct from "busy" (F12, FR-014). */
public record MemberUnschedulable(String memberId, UnschedulableReason reason) {}
