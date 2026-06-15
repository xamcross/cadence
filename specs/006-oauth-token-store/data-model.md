# Phase 1 Data Model — OAuth Token Store (Calendar Connections)

**Feature**: F01.1 | **Branch**: `006-oauth-token-store` | **Date**: 2026-06-15

Two new collections + three enums. All secret/PII fields encrypted at rest via the registered `PiiStringConverter` (research D2). No change to existing collections except a `MongoPiiConfig` converter registration and three new `AuthEventType` values.

---

## 1. `CalendarConnection` — `@Document("calendarConnections")`

One member's authorization to one calendar provider. Addressed only by the natural key; never by a credential value (research D3).

| Field | Type | Encrypted | Notes |
|---|---|---|---|
| `id` | `String` (ObjectId hex) | — | PK |
| `workspaceId` | `String` | — | scope |
| `memberId` | `String` | — | owning member (internal id) |
| `provider` | `CalendarProvider` (enum) | — | `GOOGLE` \| `MICROSOFT` |
| `status` | `ConnectionStatus` (enum) | — | `CONNECTED` \| `NEEDS_RECONNECTION` (absence of the doc = "Not connected") |
| `refreshToken` | `String` | **yes** | long-lived credential; `@Field(write = NON_NULL)` |
| `accessToken` | `String` | **yes** | cached short-lived credential; null until first use; `@Field(write = NON_NULL)` |
| `accessTokenExpiresAt` | `Instant` | — | drives the refresh decision (with skew, D6) |
| `scope` | `String` | — | granted scope string (diagnostics) |
| `providerAccountId` | `String` | **yes** | the connected account email/subject — shown as "Connected as …"; never queried/logged |
| `tokenVersion` | `long` | — | monotonic; optimistic-CAS guard for concurrent refresh (research D5) |
| `connectedAt` | `Instant` | — | first successful connect |
| `lastRefreshAt` | `Instant` | — | last successful refresh (null if never refreshed) |
| `updatedAt` | `Instant` | — | last write |

**Validation / invariants**:
- Exactly one document per `(workspaceId, memberId, provider)` — enforced by the **unique** index (ChangeUnit006). A re-connect **upserts** in place (FR-004), bumping `tokenVersion`, replacing `refreshToken`/`accessToken`/`providerAccountId`, resetting `status=CONNECTED`.
- `refreshToken` is required for a `CONNECTED` document; if a provider returns no refresh token on consent, the connect is **rejected** (no usable document is stored — FR edge case), and the member is told to re-grant with offline access.
- A `NEEDS_RECONNECTION` document **retains** its (now-invalid) `refreshToken` (so the row persists for the member to see and re-connect) but **nulls `accessToken`** (it is worthless after `invalid_grant` and data-minimisation §VIII prefers not retaining it — Security review #7). Callers must not treat a `NEEDS_RECONNECTION` row as usable (the gate is `status==CONNECTED`, not "token present").
- **Refresh rotation (FR-013 footgun)**: a refresh response often **omits** a new refresh token (providers re-issue only sometimes). The CAS update MUST set `refreshToken` **only when the response carries one** — never blanket-`$set` it to a possibly-null response value, or a good refresh token is destroyed (Security review #8).
- **NEVER `$unset` a converter-managed field** (`refreshToken`/`accessToken`/`providerAccountId`/`pkceVerifier`): Spring feeds the `$unset` marker — not a String — to `PiiStringConverter` → `ClassCastException` (the F03 lesson). Clear with `.set(field, null)` (`PiiCrypto.encrypt(null)` is null-safe).
- Hand-written `toString()` omits `refreshToken`, `accessToken`, `providerAccountId` (defence-in-depth against accidental logging — the F03 write-only-secret lesson).

**State transitions**:
```
(absent) --connect success--> CONNECTED
CONNECTED --re-connect--> CONNECTED            (tokenVersion++, creds replaced)
CONNECTED --refresh: invalid_grant--> NEEDS_RECONNECTION
CONNECTED --refresh: transient fail--> CONNECTED   (unchanged; bounded retry, D6)
NEEDS_RECONNECTION --re-connect--> CONNECTED
{CONNECTED|NEEDS_RECONNECTION} --disconnect / member deactivation--> (absent)
```

---

## 2. `OAuthFlowState` — `@Document("calendarOAuthState")`

Single-use, short-lived record binding an in-flight authorization-code flow to the initiating member (research D4). Auto-reaped by a TTL index.

| Field | Type | Encrypted | Notes |
|---|---|---|---|
| `id` | `String` | — | the high-entropy `state` nonce (also the OAuth `state` param) |
| `workspaceId` | `String` | — | scope |
| `memberId` | `String` | — | initiating member; must equal the callback session principal |
| `provider` | `CalendarProvider` | — | which provider this flow targets |
| `pkceVerifier` | `String` | **yes** | PKCE `code_verifier`; one-time secret, encrypted at rest |
| `createdAt` | `Instant` | — | |
| `expiresAt` | `Instant` | — | TTL index target; ~10 min after create |

**Validation / invariants**:
- `callback` consumes the row with an **atomic `mongoTemplate.findAndRemove(query(where("_id").is(state)), OAuthFlowState.class)`** → single-use; the returned doc is the only read path (null = replay/expired → error redirect). A `findById`-then-`delete` is a TOCTOU race that defeats single-use and is **forbidden** — `OAuthFlowStateRepository` exposes `save` only, no plain finder (Backend review #3 / Security review #5).
- After remove, the handler asserts `expiresAt > now(clock)` AND `memberId == principal.memberId()` AND `provider == path provider` before exchanging the code (D4 double-binding). Any mismatch → error redirect, no token stored.
- **The code is exchanged at the provider selected from the consumed `state.provider`** (its token endpoint + client credentials), not the path provider — the path provider only locates the row and is a redundant equality check. This makes a mix-up/provider-confusion attack structurally impossible (Security review #3).
- The TTL index uses the native driver form `new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)` on `{expiresAt:1}` (NOT a raw `Document("expireAfterSeconds",0)`, which would create a plain field index named `expiresAt` and silently never expire — Backend review #2). Reaps abandoned flows with no scheduled task.

---

## 3. Enums (domain)

- **`CalendarProvider`** — `GOOGLE`, `MICROSOFT`. (Anything else → `UnsupportedProviderException` → 400, FR-019.)
- **`ConnectionStatus`** — `CONNECTED`, `NEEDS_RECONNECTION`. ("Not connected" is the absence of a document — never a stored value.)
- **`AuthEventType`** (existing enum, **+3 values**) — `CALENDAR_CONNECTED`, `CALENDAR_DISCONNECTED`, `CALENDAR_RECONNECT_REQUIRED`.

---

## 4. Modified existing artifacts

- **`MongoPiiConfig`** — register `PiiStringConverter` for `CalendarConnection.refreshToken`, `CalendarConnection.accessToken`, `CalendarConnection.providerAccountId`, and `OAuthFlowState.pkceVerifier` (same converter instance; one bean).
- **`AuthEventType`** — add the three calendar values (append-only; never reorder existing values).
- **`RoleService.guardedDeactivate`** — add `calendarConnections.disconnectAll(workspaceId, memberId)` next to `sessions.revokeAllForMember(memberId)` (research D12).

---

## 5. Indexes — `ChangeUnit006_CalendarOAuthIndexes` (order `"006"`)

| Collection | Index | Kind | Purpose |
|---|---|---|---|
| `calendarConnections` | `{workspaceId:1, memberId:1, provider:1}` | **unique** | one connection per pair (FR-004); the self + service lookup key |
| `calendarOAuthState` | `{expiresAt:1}` TTL | `new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)` | auto-reap abandoned in-flight flows |

Native `createIndex(new Document("expiresAt",1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))` + targeted `dropIndex(new Document("expiresAt",1))` rollback (CLAUDE.md Mongock rules; never `dropIndexes`, never reuse/rename order `"006"`). **Do NOT express the TTL as an index-key `Document("expireAfterSeconds",0)`** — that creates a plain field index, not a TTL index (Backend review #2). All index fields are non-null → no partial-index `@Field(write=NON_NULL)` footgun on the index itself.

---

## 6. The `CalendarProvider` interface (forward contract for F10/F11)

The domain abstraction the constitution Dependency Policy requires. F01.1 ships the interface **and** its token-store implementation; F10/F11 add the free/busy + event methods later.

```java
public interface CalendarProvider {
    /** Identifies which provider this implementation serves. */
    CalendarProviderId id();   // GOOGLE | MICROSOFT

    /**
     * Returns a currently-valid access token for the member, refreshing transparently if expired
     * (research D5/D6). Throws CalendarReconnectRequiredException if the grant is permanently
     * invalid (status flips to NEEDS_RECONNECTION); throws CalendarProviderTransientException after
     * bounded retry on a transient provider failure. Never returns an expired token.
     */
    String validAccessToken(String workspaceId, String memberId);
}
```

F10/F11 will widen their concrete adapters with `freeBusy(...)` / `createEvent(...)` etc., consuming `validAccessToken(...)` — they never touch the token store or refresh logic directly.

---

## 7. What is deliberately NOT modelled (out of scope → F10/F11)

- No free/busy `TimeSlot`, calendar event, or availability entity (F10/F11).
- No write-scope tokens / event-mutation credentials (F10/F11 re-consent with the write scope).
- No `emailHash`-style queryable hash on credentials (D3 — never a lookup key).
- No per-provider account *list* (one connection per provider per member; multiple Google accounts for one member is out of scope for the MVP).
