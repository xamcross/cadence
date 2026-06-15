# Phase 1 Data Model — Interview Template & Rule Engine (F12)

**Branch**: `009-interview-rule-engine` | **Date**: 2026-06-15

One **new** persisted collection (`interviewTemplates`); one **new index** on the existing `managedCalendarEvents`; transient (non-persisted) result types for the rule engine. No PII or secret is stored — only internal object-id references and instants — so **no encryption converter** is needed (asserted by a raw-driver test, mirroring `ManagedCalendarEvent`).

---

## 1. Persisted: `InterviewTemplate` (`@Document("interviewTemplates")`)

The reusable, workspace-scoped definition of an interview type.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | Mongo ObjectId hex. |
| `workspaceId` | `String` | Owning workspace. Every read/write filters on it (FR-006). |
| `name` | `String` | Recruiter free text. **NEVER logged or audited** (FR-022/023) — PII vector. |
| `status` | `TemplateStatus` enum | `ACTIVE` / `RETIRED`. Retire = soft-delete (FR-004). |
| `durationMinutes` | `int` | > 0 (FR-002). |
| `slotCadenceMinutes` | `int` | Default 15; `1..durationMinutes` (D3). |
| `bufferBeforeMinutes` | `int` | ≥ 0. |
| `bufferAfterMinutes` | `int` | ≥ 0. |
| `dailyCapPerInterviewer` | `int` | ≥ 1 (FR-002). |
| `requiredMemberIds` | `List<String>` | Member object ids; all must be free (FR-009). |
| `optionalMemberIds` | `List<String>` | Never gate a slot (FR-011/Edge); annotation-only. |
| `pools` | `List<PoolRule>` | "Any N of" panel rules (FR-010). |
| `blackouts` | `List<BlackoutPeriod>` | No free-text label (FR-001). |
| `timeZoneOverride` | `String` (nullable) | IANA zone; null → inherit workspace `timeZone` (FR-018/019). |
| `workingHoursOverride` | `WorkingHours` (nullable, **reuses F03 type**) | null → inherit workspace `workingHours`. |
| `createdByMemberId` | `String` | Actor id only — never an email/display-name snapshot. |
| `createdAt` / `updatedAt` | `Instant` | Audit metadata. |

**Embedded `PoolRule`**: `{ List<String> memberIds; int n; }` — `n` validated `1..memberIds.size()` (FR-002). The engine counts distinct positively-free members ≥ `n` (D6).

**Embedded `BlackoutPeriod`**: `{ Instant start; Instant end; }` — `end` strictly after `start` (FR-002). Absolute instants (no free-text, no naive local time).

**`TemplateStatus`** (new enum): `ACTIVE`, `RETIRED`.

**`toString()`**: explicit. MUST omit `name` (hard requirement — the one PII vector). Member-id lists MAY be included (internal ObjectIds, not PII — matching the `WorkspaceConfig`/`ManagedCalendarEvent` precedent).

### Validation rules (service layer, FR-002 / FR-024) — all value-free messages

- `durationMinutes > 0`; `slotCadenceMinutes` in `1..durationMinutes`; `bufferBeforeMinutes ≥ 0`; `bufferAfterMinutes ≥ 0`; `dailyCapPerInterviewer ≥ 1`.
- At least one of (`requiredMemberIds` non-empty, `pools` non-empty) — a template that can never form a panel is invalid.
- Each `PoolRule.n` in `1..memberIds.size()`; each pool non-empty.
- Each `BlackoutPeriod.end` strictly after `start`.
- `timeZoneOverride` (if present) a valid IANA `ZoneId`; `workingHoursOverride` (if present) end strictly after start, no overnight — the **same bounds F03 enforces**.
- **Every** member id in `requiredMemberIds ∪ optionalMemberIds ∪ pools[].memberIds` is a member of `workspaceId` (D8).
- No member id is in **both** `requiredMemberIds` and any pool, and no member id appears in **two different pools** (D8 — no member fills two seats / is double-counted).
- Per-template caps (configurable, defaults): distinct members ≤ 25, pools ≤ 10, blackouts ≤ 50 (FR-024 — bound the availability fan-out / probe surface).

### State transitions

`(absent) → ACTIVE` (create) → `ACTIVE` (edit, re-validated) → `RETIRED` (retire, soft). `RETIRED` is terminal for new scheduling; the document is never hard-deleted (FR-004). No un-retire path in the MVP (out of scope; add later if needed).

---

## 2. Modified: `managedCalendarEvents` (existing F10 collection) — new index + count query

**No schema change to the document.** Add:

- **Index** (ChangeUnit008): `{ workspaceId: 1, memberId: 1, startAt: 1 }` (non-unique) — backs the daily-cap count (D5/D7).
- **Repository query**: `long countByWorkspaceIdAndMemberIdAndStatusNotInAndStartAtGreaterThanEqualAndStartAtLessThan(String workspaceId, String memberId, Collection<EventStatus> excluded, Instant dayStart, Instant nextDayStart)` — `excluded = [DELETED, CLEANUP_INCOMPLETE]` (exclusion list, future-proof — a later "live" status counts by default); **half-open** `[dayStart, nextDayStart)` (NOT `Between`, which is inclusive both ends → next-midnight double-count). `dayStart/nextDayStart` = the zone-relative civil-day bounds as `Instant`s. *(Engine reads once per required member over the clamped window and buckets by civil day in memory — one read/participant, D5 efficiency note.)*

---

## 3. Transient (NOT persisted) — rule-engine I/O

These are method arguments / return types, never written to Mongo.

**`SlotComputationRequest`**: `{ String workspaceId; String templateId; LocalDate rangeStart; LocalDate rangeEnd; }` — the range is civil dates in the applicable zone; resolved to a clamped `Instant` window before calling `AvailabilityService` (FR-017).

**`ComputedSlot`**: `{ Instant start; Instant end; String zoneId; List<String> requiredMemberIds; Map<Integer,List<String>> qualifyingByPoolIndex; }` — one offerable slot. `qualifyingByPoolIndex` maps each pool's index → the member ids that satisfy that pool's quorum (D6). Content-free. Ordering: slots ascending by `start`; member ids ascending (D14).

**`SlotComputationResult`**: `{ List<ComputedSlot> slots; boolean windowClamped; List<MemberUnschedulable> unschedulable; }` — `windowClamped` signals FR-017 clamping; `unschedulable` lists members whose status ≠ `DATA` with a **distinguishable reason** (mapped 1:1 from `AvailabilityStatus`: `NOT_CONNECTED` / `NEEDS_RECONNECTION` / `TEMPORARILY_UNAVAILABLE`) so F13/F14 can surface why a required member blocked slots (FR-014).

**`MemberUnschedulable`**: `{ String memberId; UnschedulableReason reason; }` where `UnschedulableReason` mirrors the non-`DATA` `AvailabilityStatus` values.

---

## 4. Relationships

```
WorkspaceConfig (F03) ──timeZone, workingHours──▶ InterviewTemplate (inherits unless overridden, by reference at compute time)
Member (F01) ──id referenced by──▶ InterviewTemplate.{required,optional,pools[].memberIds}  (membership validated, D8)
InterviewTemplate ──drives──▶ RuleEngine ──reads──▶ AvailabilityService.query (F10/F11, unchanged) ──▶ MemberAvailability[]
RuleEngine ──counts──▶ managedCalendarEvents (cap, D5) ──▶ ComputedSlot[]  (consumed by F13/F14)
```

---

## 5. Index summary (ChangeUnit008, order "008")

| Collection | Index | Unique | Purpose |
|---|---|---|---|
| `interviewTemplates` | `{workspaceId, status}` | no | List active templates per workspace (FR-004/006). |
| `managedCalendarEvents` | `{workspaceId, memberId, startAt}` | no | Daily-cap count per member per civil day (D5). |

Both fields-non-null → no `@Field(write=NON_NULL)` partial-index footgun (CLAUDE.md F01 lesson). Native `createIndex`; targeted `dropIndex` rollback (never `dropIndexes()`).
