# Phase 0 Research — Interview Template & Rule Engine (F12)

**Branch**: `009-interview-rule-engine` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)

All decisions are grounded against the **actual F03/F10/F11 source** (`AvailabilityService`, `MemberAvailability`/`BusyInterval`/`AvailabilityStatus`, `ManagedCalendarEvent` + repo, `ChangeUnit007`, `WorkspaceConfig`/`WorkingHours`, `WorkspaceConfigController`, `CalendarApiProperties`, `Role`, `AuthEventType`). No NEEDS CLARIFICATION remain.

---

## D1 — Build the rule engine on `AvailabilityService.query(...)` UNCHANGED

**Decision**: The engine reads panel availability through the existing `AvailabilityService.query(workspaceId, windowStart, windowEnd, memberIds)` → `List<MemberAvailability>` with **no change to that service or its model**. `MemberAvailability` already carries exactly what the engine needs: a per-member `AvailabilityStatus` and a `List<BusyInterval>` (absolute `Instant`s, content-free). The engine issues **one** `query` for the whole panel over the whole (clamped) window — never per-day or per-slot.

**Rationale**: `AvailabilityService` already (a) fans out a panel on the bounded `calendarFanoutExecutor` (SC-007 perf), (b) clamps the window to `maxWindow` (60d) (FR-017), (c) returns a **distinguishable** status for not-connected / needs-reconnection / transient (`NOT_CONNECTED`/`NEEDS_RECONNECTION`/`TEMPORARILY_UNAVAILABLE`) — never silently "free". `status == DATA` with an empty busy list is the only "genuinely free" signal. This is the load-bearing input for the FR-014 fail-safe and it already exists.

**Alternatives rejected**: A per-member or per-day re-query (blows the perf budget and breaks determinism — the snapshot would shift between reads); widening the availability model (unnecessary — busy intervals + status suffice).

---

## D2 — Slot generation algorithm (deterministic interval arithmetic)

**Decision**: For each civil day in the (zone-resolved) target range:
1. Compute the day's working-hours window as `[ZonedDateTime(day, whStart, zone), ZonedDateTime(day, whEnd, zone)]` → two `Instant`s.
2. Generate candidate slot **starts** on the configured cadence, anchored to the working-day start in the applicable zone (D3), advancing `cadenceMinutes` at a time as `ZonedDateTime` (so wall-clock arithmetic is DST-correct, D4), stopping when `start + duration + bufferAfter` would exceed the working-window end.
3. For each candidate `[start, end]` (end = start + duration), reject if it overlaps any blackout, or if `[start − bufferBefore, end + bufferAfter]` overlaps any **required** participant's busy interval, or any required participant's status ≠ `DATA` (fail-safe), or the daily cap binds for any required participant (D5).
4. For each pool, count **distinct** pool members whose status == `DATA` and whose busy intervals do not overlap `[start − bufferBefore, end + bufferAfter]`; the slot is eligible only if every pool reaches its `N`; record the qualifying members **per pool** (D6).
5. Emit a `ComputedSlot` (start/end `Instant` + zone + required set + per-pool qualifying sets).

All overlap tests are half-open interval intersection on `Instant`s: `aStart < bEnd && bStart < aEnd`.

**Rationale**: Pure `java.time` (`ZonedDateTime`/`ZoneId`/`Instant`) handles DST by construction; no library, no `@Scheduled`, no new dependency. Day-at-a-time keeps the algorithm O(days × candidateStarts × intervals) which is trivial for a 14-day / ≤8-member panel.

**Alternatives rejected**: A constraint solver (massive over-engineering for "any N of pool" + interval checks, violates §I YAGNI); free-interval merging then slicing (equivalent result, more code, harder to keep the per-pool annotation).

---

## D3 — Slot-start cadence: default 15 min, configurable, anchored to working-day start

**Decision**: `slotCadenceMinutes` on the template, **default 15**, validated `1..(duration)` (cadence cannot exceed duration; must divide evenly into the working window is **not** required). Candidate starts are anchored to the **working-day start** in the applicable zone (e.g. with WH 09:00 and cadence 15 → 09:00, 09:15, …), not to midnight or the request instant — so the offered grid is stable and DST-independent of the request time.

**Rationale**: 15 min is the conventional scheduling granularity and matches Google/Outlook UIs; anchoring to the working-day start makes the grid deterministic (FR-016) and human-sensible (no 09:07 slots). Pinning the anchor here (not "a plan detail") resolves the QA/BA finding that an unanchored cadence is two-correct-implementations ambiguous.

**Alternatives rejected**: Midnight-anchored (produces off-grid starts when WH start isn't a cadence multiple); request-instant-anchored (non-deterministic across calls).

---

## D4 — DST correctness: zone-resolved wall-clock generation, Instant comparison

**Decision**: Working-hours windows and the cadence walk are computed with `ZonedDateTime` in the applicable zone (template override zone, else workspace `timeZone`). The **non-existent** spring-forward local time is detected and skipped by a **`LocalDateTime` round-trip check**: construct `ZonedDateTime.of(ldt, zone)`, then compare `.toLocalDateTime()` to the input `ldt` — if they differ, `ldt` fell in a DST gap (Java silently shifts a gap local-time *forward* by the gap; it does not throw), so that candidate start is rejected (equivalently `zone.getRules().getTransition(ldt) != null && transition.isGap()`). **NOTE**: `withEarlierOffsetAtOverlap` is NOT used for gap detection — it only disambiguates the *fall-back overlap* (two valid offsets), a separate concern. The **repeated** fall-back hour is offered once because each cadence step is a distinct `Instant`. The daily cap counts per **civil day** in the zone regardless of the day being 23 h or 25 h. All busy-overlap and blackout comparisons are on `Instant`s.

**Rationale**: This is the exact model F10/F11 already use for event writes (absolute instants + zone); F03 explicitly deferred "DST handling" to F12. `ZonedDateTime` resolves gaps/overlaps natively; we just assert the round-trip to reject the gap.

**Alternatives rejected**: Naive `LocalDateTime` math (the classic DST silent-failure the spec forbids); storing slots as local time (ambiguous at the boundary).

---

## D5 — Daily cap: new index + count query on `managedCalendarEvents`, required-only at compute time

**Decision**: The per-interviewer daily cap counts **Cadence-managed interviews** for the member on the slot's civil day. This needs a query that **does not exist today**:
- Add `ManagedCalendarEventRepository.countByWorkspaceIdAndMemberIdAndStatusNotInAndStartAtGreaterThanEqualAndStartAtLessThan(workspaceId, memberId, Collection<EventStatus> excluded, Instant dayStart, Instant nextDayStart)` — **exclusion list** `{DELETED, CLEANUP_INCOMPLETE}` (the spec's fail-safe phrasing, future-proof: a later "live" status like a reschedule state is counted by default, whereas an inclusion `StatusIn[CREATED]` would silently drop it — Backend reviewer). The day bound is **half-open** `[dayStart, nextDayStart)` via `GreaterThanEqual…LessThan` — NOT Spring Data `Between` (which is inclusive on both ends and would double-count an event at the next-day-midnight boundary, an off-by-one especially live on the 23h/25h DST days).
- Add index `managedCalendarEvents {workspaceId, memberId, startAt}` (a **second** index on the existing F10 collection — see D7).
- **Efficiency (Backend NIT)**: rather than one `count` per (member, day), the engine does one `find` over the clamped window per required member and buckets the rows by zone-relative civil day in memory — one read per required participant, consistent with the D14 "one read per participant" determinism story.

The cap is enforced **against required participants** at compute time: a running per-(memberId, civilDay) counter is seeded from the DB count and incremented as the engine offers slots that require that member, so within one computation the engine never offers a set that exceeds the cap (SC-002, within-computation case). **Pool-member** cap enforcement is **deferred to F13** (a pool member is not bound to a slot until booking); the spec records that F13 MUST re-validate the cap atomically at reservation, and that the cap is otherwise only intra-computation-consistent.

**Rationale**: Generic busy time is already excluded by the required-free check (D2); the cap is specifically about *Cadence's own* interviews, whose only source is `managedCalendarEvents`. The `status` filter and zone-relative day boundary were both flagged by the Backend reviewer and are correctness-load-bearing.

**Alternatives rejected**: Counting all busy intervals as "interviews" (wrong — counts unrelated meetings); a full scan without the index (violates the F00.1 covering-index gate and the SC-007 budget); binding pool members at compute time (breaks determinism and the eligibility model).

---

## D6 — "Any N of pool": distinct positively-free members, per-pool annotation

**Decision**: A pool reaches quorum only when at least `N` **distinct** members have `status == DATA` and no busy overlap; an unknown-status pool member is **not counted** (same fail-safe as required). The `ComputedSlot` carries, per pool, the set of qualifying member ids (and separately the required-member set). A member that is both required and in a pool — **and** a member appearing in two different pools — is **rejected at template validation** (D8) so no member fills two seats / is double-counted (QA reviewer: the two-pools case must be closed too, not just required+pool). If a non-rejected single-pool member legitimately appears once, it is counted once per slot for both the free-check and the cap.

**Rationale**: Resolves the BA "flat set is ambiguous with multiple pools" and the QA "unknown pool member silent-free" findings. Per-pool annotation is exactly what F13 needs to finalise each pool independently; keeping the engine to *eligibility + annotation* (not concrete binding) preserves determinism and the stable F14 slot list.

**Alternatives rejected**: Flat qualifying set (F13 can't tell which member satisfies which pool); binding one concrete interviewer per pool at compute time (non-deterministic; F13's job).

---

## D7 — New `interviewTemplates` collection + ChangeUnit008 (TWO indexes)

**Decision**: New collection `interviewTemplates`; new `ChangeUnit008_InterviewTemplateIndexes` (order **"008"** — the highest *applied* ChangeUnit is `007`; order is derived from that, **not** the branch number `009`). The single changeset creates **two** indexes:
- `interviewTemplates {workspaceId, status}` (non-unique) — list active templates per workspace (FR-004/FR-006).
- `managedCalendarEvents {workspaceId, memberId, startAt}` (non-unique) — the daily-cap count (D5); an added index on the **existing** F10 collection.

Native `createIndex` + targeted `dropIndex` rollback (CLAUDE.md Mongock rules; never `dropIndexes()`). All indexed fields non-null → no partial-index footgun.

**Rationale**: Corrects the spec's earlier "no other new collection required" framing — no new *collection* beyond `interviewTemplates`, but a **new index on an existing collection is required** for the cap. One changeset can create both (the Backend reviewer's fix).

**Alternatives rejected**: Reusing `{workspaceId,bookingRef}` for the cap (wrong key — the cap is by member+day, not booking); a separate changeset per index (unnecessary; one logical migration).

---

## D8 — Member-reference validation (workspace membership + no dual-role)

**Decision**: At create/edit, every referenced member id (required, optional, pool) MUST be a member of the template's workspace — validated against `MemberRepository.findByWorkspaceId(workspaceId)` (membership set check). A foreign-workspace or unknown id is a value-free validation failure (no partial write). A member appearing both as required and inside a pool is rejected. Per-template caps on distinct members / pools / blackouts (FR-024) are validated here.

**Rationale**: FR-006 scopes the *template*, but the **member references inside it** are the cross-workspace leak vector the Security reviewer flagged — without this check a forged pool member id would make the engine read a foreign member's availability. Membership validation closes it; the per-template caps bound the availability fan-out (DoS/probe surface).

**This is the PRIMARY compute-path isolation control — not the role gate (D9).** The role gate authorizes *who* may compute; it does NOT stop a Recruiter from computing against member ids outside their workspace. So: (a) the engine MUST pass `AvailabilityService.query` **only** the member ids read from the *persisted, validation-passed* template document — never a request-supplied list (a compute request carries only a template id + date range, no member list, by design — contract §B); (b) a `RuleEngine` test asserts the id set handed to `AvailabilityService` equals the persisted template's validated members. **Compute-time backstop**: even with a stale membership (a member added then later moved/left), `AvailabilityService.resolve` scopes every connection lookup to `findByWorkspaceIdAndMemberId(workspaceId, …)`, so a stale id queried under this workspace returns the member's connections *in this workspace only* (or `NOT_CONNECTED`) — a member who moved workspaces cannot have their new-workspace calendar read under the old workspace id. So write-time validation + the service's `workspaceId`-scoped lookup are belt-and-braces.

**Alternatives rejected**: Validating only at compute time (a foreign id would already be persisted and could leak via a later compute); trusting the client (never); relying on the role gate alone for isolation (it does not scope members).

---

## D9 — RBAC: Recruiter+Admin on management AND compute; `AvailabilityService` stays privileged

**Decision**: A single `InterviewTemplateController` under `/api/internal/interview-templates` with class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` — covering CRUD **and** the slot-preview/compute endpoint. Hiring Manager, Interviewer, Read-only get 403. The compute path reaches `AvailabilityService.query` (the privileged internal primitive) only behind this gate; the `RbacEndpointInventoryTest` covers every handler (a missing `@PreAuthorize` reds the build). `SessionService.Principal` supplies `workspaceId`/`memberId`/`role`.

**Rationale**: Resolves the Security "compute path has no declared role gate" finding. Recruiter/Admin are the scheduling roles, so seeing computed slots (busy-derived, content-free) for the panel is within their remit; lower roles must not enumerate member availability. Matches the F03 controller pattern (class-level `@PreAuthorize` as the single source of truth).

**Alternatives rejected**: `isAuthenticated()` on compute (lets any role probe availability — the F10 `AvailabilityService` warning); separate gates per method (the class-level annotation is the established pattern and inventory-test-friendly).

---

## D10 — Logging / audit discipline + new audit event types

**Decision**:
- **Never** log the template `name` or any free-text; log only `workspaceId`/`templateId`/`memberId` object ids as Strings. Enums (`AvailabilityStatus`, template status) are logged via `.name()` Strings, **never** passed to `StructuredArguments.kv(...)` (the F01.1 logstash Jackson-3 `NoSuchFieldError` footgun).
- **Value-free validation messages**: field + rule code, never echoing the submitted value (the F04 sanitisation discipline) — so a PII-laden bad value can't leak into the response or logs.
- Add **four** append-only `AuthEventType` values: `INTERVIEW_TEMPLATE_CREATED`, `INTERVIEW_TEMPLATE_UPDATED`, `INTERVIEW_TEMPLATE_RETIRED`, and `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` (no PII payload, ids only). The refused-compute-against-retired-template attempt gets its **own** value (`COMPUTE_REFUSED`) rather than reusing `_RETIRED` (Security reviewer: overloading the retire lifecycle event with a refused-compute is a semantic mismatch that muddies any future alerting on retire actions; a 4th append-only value is trivial and clean).
- Extend the CI PII/log scan with an `interviewTemplates` template-name sentinel.

**Rationale**: FR-022/FR-023/SC-010 + the Security reviewer's "template name is the one PII vector" finding. Adding three enum values is permitted (the enum is append-only — never reorder); F11 reused existing values only because none were needed, not because new ones are forbidden.

**Alternatives rejected**: Auditing the full template body (PII risk + bloat); a generic `WORKSPACE_CONFIG_CHANGED` reuse (wrong semantics, not template-scoped).

---

## D11 — §II demonstrable end-to-end leg: template management UI + recruiter slot preview

**Decision**: F12 ships a real Angular → Spring Boot → MongoDB flow: a **template management** feature (list / create / edit / retire) for Recruiter/Admin, plus a **slot-preview** action (dry-run of the engine for a chosen template + date range, rendering the computed compliant slots and, per slot, the qualifying participants — content-free). The atomic reservation, candidate email, and pipeline status are F13; the candidate-facing slot-picker is F14.

**Rationale**: §II requires every increment be demonstrable browser-to-DB. Template CRUD is inherently a full-stack flow; the slot-preview is the analogue of F10's "availability preview" §II leg — it exercises the rule engine end-to-end against the stubbed providers without needing F13. No candidate UI is faked.

**Alternatives rejected**: Backend-only delivery (violates §II — "backend-only work presented as a shipped feature is PROHIBITED"); building a candidate slot-picker now (that's F14, §I YAGNI).

---

## D12 — Performance: one panel read + one indexed cap-count per required member

**Decision**: Per computation: exactly one `AvailabilityService.query` (panel-wide, bounded-parallel, already < 5 s for 5 members per F10 SC-001) + one indexed `count` per **required** member per distinct civil day touched. The rule evaluation itself is in-memory interval arithmetic. SC-007 is a **deterministic compute budget** measured against the stub (no live network), pinned in this plan.

**Rationale**: The cap count is negligible once the D7 index exists; the dominant cost is the single availability fan-out which already meets the panel target. Determinism (FR-016) requires the single snapshot + an injected `Clock` for any "future-only" logic (the project already mandates an injected `Clock`, CLAUDE.md F01).

**Compute budget**: rule evaluation (excluding the availability read and DB counts) for a 5-member panel, two pools, 14-day window, 15-min cadence, 09:00–17:00 WH < **50 ms** on CI hardware (asserted against a seeded in-memory snapshot, no network).

---

## D13 — No new dependency, service, scheduler, or topology

**Decision**: Pure in-stack: `java.time` arithmetic, Spring Data Mongo repo, an `@Service` rule engine, a `@RestController`, Angular standalone components. No broker/cache/queue, no `@Scheduled` task (computation is request-scoped), no SDK, no new runtime dependency. Tests reuse the F10/F11 JDK `HttpServer` stub for seeded availability (no live calendar creds).

**Rationale**: Satisfies C2/C4/C7. The engine is exactly the kind of pure business logic the fixed stack already supports.

---

## D14 — Injected `Clock` for reproducibility; deterministic ordering

**Decision**: The engine takes the existing injected `java.time.Clock` (the F01 pattern, overridable by the test `MutableClock`) for any "now/future-only" gate. Output ordering is fixed: slots ascending by start `Instant`; per-pool qualifying member ids ascending by id. The engine performs exactly one availability read per participant per computation (no within-computation retry variance) so determinism is defined over a fixed snapshot.

**Rationale**: Resolves the QA "transient-failure vs determinism collision" and "set-ordering flakiness" findings. Stable ordering makes the contract/equality assertions reproducible.
