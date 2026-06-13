# Data Model: Project Scaffold & Build Pipeline

**Feature**: 001-project-scaffold  
**Phase**: 1 — Design  
**Date**: 2026-06-13

This feature introduces two MongoDB collections that underpin the scheduler infrastructure (F00.2) and the startup index bootstrapping (F00.1). All other domain collections (candidates, interviews, etc.) have their schemas defined by the features that own them; F00 only declares the **indexes** those collections will eventually need.

---

## Collection 1: `schedulerCheckpoints`

Stores the execution state of each `@Scheduled` background task to enable idempotent, missed-fire-safe execution.

### Document Schema

```json
{
  "_id": "ObjectId",
  "taskName": "string — unique, e.g. 'noShowConfirmationTask'",
  "status": "string — enum: RUNNING | COMPLETED",
  "startedAt": "ISODate",
  "completedAt": "ISODate | null",
  "missedFireReplayedAt": "ISODate | null"
}
```

### Field Rules

| Field | Required | Notes |
|---|---|---|
| `taskName` | Always | Unique across all tasks; matches the bean method name |
| `status` | Always | `RUNNING` written at task start; `COMPLETED` written at task end |
| `startedAt` | Always | Set when status transitions to `RUNNING` |
| `completedAt` | On completion | Null while status is `RUNNING` |
| `missedFireReplayedAt` | On replay only | Set when startup detects a stale `RUNNING` checkpoint and triggers replay |

### State Transitions

```
(not exists) ──[task starts]──► RUNNING ──[task completes]──► COMPLETED
                                   │
                         [crash / kill -9]
                                   │
                          (stays as RUNNING)
                                   │
                [app restarts, startedAt > 15 min ago]
                                   │
                        [replay triggered, missedFireReplayedAt set]
                                   │
                               COMPLETED
```

### Indexes

| Index | Fields | Options | Purpose |
|---|---|---|---|
| Primary index | `taskName: 1` | unique | Atomic upsert; prevents concurrent duplicate task records |

---

## Collection 2: `deadLetterRecords`

Stores a record of every `@Scheduled` task that failed with an uncaught exception. Contains no personal information.

### Document Schema

```json
{
  "_id": "ObjectId",
  "taskName": "string",
  "failedAt": "ISODate",
  "errorType": "string — Java exception class name",
  "errorSummary": "string — sanitised message, no PII",
  "affectedCandidateId": "string | null — internal ObjectId string only, not email or name",
  "alertSentAt": "ISODate | null"
}
```

### Field Rules

| Field | Required | Notes |
|---|---|---|
| `taskName` | Always | Identifies which `@Scheduled` task failed |
| `failedAt` | Always | Timestamp of the uncaught exception |
| `errorType` | Always | Exception class name only (e.g., `NullPointerException`) |
| `errorSummary` | Always | Sanitised error message; any value resembling an email or name MUST be replaced with `[REDACTED]` |
| `affectedCandidateId` | When applicable | Internal MongoDB ObjectId string — never email, name, or phone |
| `alertSentAt` | After alert dispatch | Null until `EmailSender.sendSystemAlert` succeeds |

### Indexes

None required — dead-letter records are low-volume and queried by admin UI only (future feature); a collection scan is acceptable for MVP.

---

## Startup Index Bootstrap (F00.1)

The following indexes are declared by F00.1 and created by Mongock at every application startup. The collections they target do not need to exist at startup — Mongock creates the index on the collection when the changeset runs (MongoDB creates the collection automatically on first index creation).

Each future feature's `plan.md` MUST declare which indexes it depends on from this list and MUST NOT access the collection via a query path that lacks a covering index.

### Index Manifest

| Collection | Index fields | Options | Declared for |
|---|---|---|---|
| `interviews` | `{ scheduledAt: 1, confirmationStatus: 1 }` | — | F23 no-show cascade queries |
| `candidates` | `{ workspaceId: 1, lastContactAt: 1 }` | — | F31 SLA breach scanner |
| `feedbackRequests` | `{ interviewEventId: 1, submittedAt: 1 }` | — | F32 reminder escalation |
| `schedulingTokens` | `{ token: 1 }` | unique | F13/F14 scheduling token lookup |
| `auditLog` | `{ candidateId: 1, occurredAt: -1 }` | — | F04 audit queries |
| `schedulerCheckpoints` | `{ taskName: 1 }` | unique | F00.2 @Scheduled idempotency |

### Mongock Changeset Structure

```
backend/src/main/java/com/cadence/config/migration/
└── ChangeUnit001_BootstrapIndexes.java   // @ChangeUnit id="001", order="1"
```

The changeset class is annotated `@ChangeUnit(id = "001-bootstrap-indexes", order = "001", author = "system")`. It uses the native MongoDB driver API — `mongoTemplate.getCollection(collectionName).createIndex(new Document(field, direction), new IndexOptions().unique(true))` — for each index entry (see the implementation note in `CLAUDE.md`: the Spring Data 4.x `indexOps(...).createIndex(Index)` overload is not reliably accessible in all contexts, so the driver API is used directly). `createIndex` with an identical spec is idempotent (a no-op), so re-running the changeset is safe; conflicting index definitions surface as a `MongoCommandException`, which is the correct migration behaviour. Rollback uses targeted `dropIndex(new Document(...))` per index — never `dropIndexes()`, which would destroy indexes created by other changesets on the same collection.

**Mongock changelog collection**: Mongock stores its execution history in a `mongockChangeLog` collection. This collection is NOT listed in the domain index manifest above — it is an internal Mongock concern. The `IndexBootstrapTest` MUST verify indexes by querying `indexOps(collectionName).getIndexInfo()` rather than relying on whether Mongock ran; this decouples the index presence assertion from Mongock's own changelog state.

---

## Domain Java Types

### `SchedulerCheckpoint.java`

```
package com.cadence.domain;

@Document(collection = "schedulerCheckpoints")
public class SchedulerCheckpoint {
    @Id private String id;
    private String taskName;          // unique
    private CheckpointStatus status;  // enum: RUNNING, COMPLETED
    private Instant startedAt;
    private Instant completedAt;      // nullable
    private Instant missedFireReplayedAt; // nullable
}

public enum CheckpointStatus { RUNNING, COMPLETED }
```

### `DeadLetterRecord.java`

```
package com.cadence.domain;

@Document(collection = "deadLetterRecords")
public class DeadLetterRecord {
    @Id private String id;
    private String taskName;
    private Instant failedAt;
    private String errorType;
    private String errorSummary;       // sanitised, no PII
    private String affectedCandidateId; // nullable, internal ID only
    private Instant alertSentAt;       // nullable
}
```

---

## Validation Rules

- `SchedulerCheckpoint.taskName` MUST match the bean method name of the `@Scheduled` task exactly; enforced by a naming convention test in the test suite.
- `DeadLetterRecord.errorSummary` MUST NOT contain values matching `.*@.*\\..*` (email pattern) or a configurable PII regex; enforced by a sanitiser utility applied before the record is saved.
- No field in either collection stores a candidate's name, email address, or phone number.
