# Data Model: Workspace Setup & Configuration (F03)

**Date**: 2026-06-14 | **Feature**: `004-workspace-config` | Derived from [spec.md](./spec.md) Key Entities + [research.md](./research.md).

Two new collections (`workspaceConfig`, `workspaceLogo`) and a minimal extension to the existing `authAuditLog`. No change to `members`/`sessions`.

---

## Collection: `workspaceConfig`  (NEW — one document per workspace)

`@Document(collection = "workspaceConfig")` → `WorkspaceConfig.java`

| Field | Type | Notes |
|---|---|---|
| `id` | `String` `@Id` | Mongo ObjectId |
| `workspaceId` | `String` | **Unique index** (D12). The singleton key. |
| `configuredAt` | `Instant` (nullable) | `null` ⇒ *unconfigured*; set once on wizard completion ⇒ *configured* (one-way, FR-002/FR-006) |
| `name` | `String` | Workspace display name (required at setup) |
| `timeZone` | `String` | IANA zone id, validated `ZoneId.of(...)` (FR-005) |
| `workingHours` | `WorkingHours` (embedded) | `{ start: LocalTime, end: LocalTime }`, end > start, no overnight (FR-005) |
| `slaSilenceWindowDays` | `int` | 1–30 inclusive (FR-005). Consumed by F31. |
| `retentionPeriodDays` | `int` | 30–3650 inclusive (FR-005/FR-021). Consumed by F04. |
| `retentionAcknowledgedAt` | `Instant` | Set with `configuredAt`; the GDPR-gate evidence (FR-004). Non-null required to be configured. |
| `brandColor` | `String` (nullable) | `^#[0-9A-Fa-f]{6}$` when set; null ⇒ default `#1F2937` (FR-012/FR-013) |
| `hasLogo` | `boolean` | True ⇒ a `workspaceLogo` doc exists (avoids touching the logo collection on a config read) |
| `emailSendingDomain` | `String` (nullable) | RFC-1035 hostname format when set (FR-015) |
| `emailProviderCredential` | `String` (nullable) | **STRUCTURALLY WRITE-ONLY.** Encrypted at rest via `PiiStringConverter` (D2). Annotated `@JsonIgnore`; `toString()` hand-overridden to omit it; on NO response DTO. NEVER logged. |
| `templateLocks` | `Map<String,Boolean>` | template key → locked (FR-019/D10). Empty map default. |
| `createdAt` | `Instant` | |
| `updatedAt` | `Instant` | bumped on every write |

**Invariants**
- Exactly one document per `workspaceId` (unique index + conditional upsert, D4). **No document exists until `POST /setup` creates it** — `GET /config` and `/me` reads are strictly read-only and never get-or-create (D4 / review BE-1).
- *Configured* ⟺ `configuredAt != null` ⟺ `retentionAcknowledgedAt != null` (the wizard sets both atomically; FR-004). `configuredAt` and `retentionAcknowledgedAt` are **immutable after setup** — no PATCH/PUT path mutates them (review SEC-2); the authoritative acknowledgment evidence is the append-only `WORKSPACE_CONFIGURED` audit row, not this field.
- `emailProviderCredential` is never read back into a DTO; only `credentialSet = (emailProviderCredential != null)` is exposed (FR-017).
- No field derived from the credential is stored (FR-016).

**State transition** (single, one-way):
```
unconfigured (configuredAt == null)
   --[POST /setup, valid + retention acknowledged]-->  configured (configuredAt set)
configured --[POST /setup again]--> refused 409 (FR-006); ongoing edits go via PATCH/PUT
```

---

## Collection: `workspaceLogo`  (NEW — one document per workspace)

`@Document(collection = "workspaceLogo")` → `WorkspaceLogo.java`

| Field | Type | Notes |
|---|---|---|
| `id` | `String` `@Id` | |
| `workspaceId` | `String` | **Unique index** (D12) |
| `bytes` | `byte[]` (Mongo `Binary`) | Verified raster bytes, ≤ 1 MB (D6) |
| `contentType` | `String` | Verified `image/png` or `image/jpeg` (from magic byte, not client) |
| `size` | `int` | byte length |
| `updatedAt` | `Instant` | |

**Invariants**: at most one doc per workspace; presence mirrored by `WorkspaceConfig.hasLogo`. Set/replaced atomically on upload; deleted on unset (then `hasLogo=false`).

---

## Collection: `authAuditLog`  (MODIFIED — reuse, minimal extension)

`AuthAuditEvent.java` gains two nullable non-PII fields; `AuthEventType` gains two values.

| New field | Type | Notes |
|---|---|---|
| `oldValue` | `String` (nullable) | Non-PII. Populated only for `retention_period` change (old days) — FR-023 |
| `newValue` | `String` (nullable) | Non-PII. Retention new days, or the acknowledged retention value on `WORKSPACE_CONFIGURED` |

New `AuthEventType` values:
- `WORKSPACE_CONFIGURED` — setup completion. `memberId`=actor, `newValue`=acknowledged retention days, `outcome="setup_completed"` (FR-004/SC-003).
- `WORKSPACE_CONFIG_CHANGED` — any later change. `outcome` ∈ {`name`,`time_zone`,`working_hours`,`sla_window`,`retention_period`,`branding`,`logo`,`email_config`,`template_lock`}. `oldValue`/`newValue` set only for `retention_period`.

**Append-only**: `AuthAuditService` only `save()`s; no update/delete path exists on `AuthAuditEventRepository` (FR-026/SC-013). The credential value is never written to an audit row (set/rotate/unset record `email_config` only).

---

## Indexes (Mongock `ChangeUnit004_WorkspaceConfigIndexes`, order `004`)

| Collection | Index | Unique | Purpose |
|---|---|---|---|
| `workspaceConfig` | `{ workspaceId: 1 }` | ✅ | Singleton enforcement + lookup (D1/D4/D12) |
| `workspaceLogo` | `{ workspaceId: 1 }` | ✅ | Singleton logo lookup |

`authAuditLog` indexes are unchanged (F01/F02 already cover member-keyed queries). No nullable/partial index is introduced, so the F01 `@Field(write=NON_NULL)` partial-index footgun does not apply.

---

## DTOs (`WorkspaceDtos.java`) — request vs response asymmetry (FR-017)

**Requests** (Admin, may carry the credential):
- `SetupRequest { name, timeZone, workingHours{start,end}, slaSilenceWindowDays, retentionPeriodDays, retentionAcknowledged: boolean }` — `retentionAcknowledged` must be `true` (FR-004).
- `SettingsPatch { name?, timeZone?, workingHours?, slaSilenceWindowDays?, retentionPeriodDays? }` — partial; each present field validated.
- `BrandingRequest { brandColor }` (+ multipart file for logo on a separate endpoint).
- `EmailConfigRequest { sendingDomain, credential }` — `credential` write-only. **A class (not a record) with a hand-written `toString()` that omits `credential`** — a record's auto-`toString()` would print it in a `MethodArgumentNotValidException`/`BindingResult` on a validation failure (review SEC-NIT-1, the most likely leak path).
- `TemplateLockRequest { locked: boolean }`.

**Responses** (NEVER include the credential):
- `WorkspaceConfigResponse { configured, name, timeZone, workingHours, slaSilenceWindowDays, retentionPeriodDays, retentionAcknowledgedAt, brandColor, hasLogo, emailSendingDomain, credentialSet: boolean, templateLocks }` — note `credentialSet`, not the value.
- `BrandingResponse { brandColor (resolved/default), logoUrl }` — public, non-PII only.

A compile-time guard discipline: `emailProviderCredential` exists only on the domain object and request DTO; it appears on **no** response record (verified by `EmailConfigIntegrationTest` SC-006 across all roles).

---

## Validation rules (centralised in `WorkspaceConfigService` / `BrandingService`)

| Rule | Source | Failure |
|---|---|---|
| `timeZone` is a valid IANA id | FR-005 | per-field 400, no persist |
| `workingHours.end > start`, not overnight | FR-005 | per-field 400 |
| `slaSilenceWindowDays ∈ [1,30]` | FR-005 | per-field 400 |
| `retentionPeriodDays ∈ [30,3650]` (0 rejected) | FR-005 | per-field 400 |
| `retentionAcknowledged == true` to complete setup | FR-004 | 400, stays unconfigured |
| `brandColor` matches `^#[0-9A-Fa-f]{6}$` | FR-012 | per-field 400 |
| logo ≤ 1 MB, magic-byte PNG/JPEG, `ImageIO` decodes, dims ≤ 2048² | FR-012/D6 | 400, no persist; SVG rejected |
| `emailSendingDomain` RFC-1035 hostname | FR-015 | per-field 400 |
| setup on already-configured workspace | FR-006 | 409, no second transition |
| any non-Admin caller (internal endpoints) | FR-008 | 403 (F02 handler) |

All validation is **all-or-nothing** per submission (no partial writes, FR-005/SC-008).
