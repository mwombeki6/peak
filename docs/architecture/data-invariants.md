# Schema Ownership And Invariant Matrix

| Invariant | Scope | Enforcement |
|---|---|---|
| Tenant-safe keys | Every tenant/property child | Composite tenant foreign keys, service binding and RLS tests |
| RLS and `FORCE ROW LEVEL SECURITY` | Tenant-owned operational tables | Migrations plus runtime-role integration tests |
| Runtime grants | `pms_app`, `pms_platform`, `pms_worker`, support | Explicit grants; roles cannot own tables or use `BYPASSRLS` |
| Append-only history | Audit, consent, attempts, snapshots, stock and custody history | Trigger guards and negative mutation tests |
| Accountable financial exceptions | Night-audit discrepancies | Unique daily cases, active owner/due state, immutable evidence/events, and live source revalidation |
| Financial immutability | Posted charges/payments, invoices, fiscal documents, journals | Status-aware trigger guards and reconciliation functions |
| Money and currency | Financial and stock valuation state | Fixed numeric precision, positive/zero checks and explicit ISO currency |
| Business date | Property close inputs | Property timezone attribution and immutable close snapshot provenance |
| Time | Operational timestamps | `timestamptz`; business-local dates remain explicit `date` values |
| Hashes and evidence | Identity, reports, fiscal/provider callbacks | Versioned keyed hashes or SHA-256 evidence; secrets never stored in evidence |
| Document sequences | Invoice, credit and operational numbers | Locked database allocator with uniqueness constraints |
| Retention | Idempotency, realtime, reports and provider evidence | Bounded claim/cleanup functions with provenance and replay-safe state |
| Soft delete | Mutable master data | `deleted_at` plus uniqueness/read predicates; financial/audit history is never soft-deleted |
| Tenant control | SaaS lifecycle | Desired/actual state, optimistic versions and durable workflows; commercial restriction never interrupts an in-house stay |
| Privileged support | Tenant support access | Open ticket, exact permission, MFA, separate approver, bounded duration/uses, exact session selector and immutable evidence |
| Commercial capacity | Properties, rooms, users, outlets | Database advisory lock plus effective plan/override entitlement assertion in the owning write transaction |
| Configuration rollout | Portfolio and feature control | Immutable hashed revisions, deterministic precedence, canary/apply/rollback states and attributable approvals |
| Definer name resolution | Every `SECURITY DEFINER` function | `SET search_path = pg_catalog, public, pg_temp`. Omitting `pg_temp` searches it first, letting a caller shadow the tables the body reads; a catalog assertion fails the build if any function omits it |

Applied migrations `V1–V74` are immutable. `V75` adds evidence-backed tenant
activation and secure initial-administrator bootstrap; `V74` added authenticated
frontend session bootstrap route contracts. Later database remediation starts at
`V76`; application-only boundary fixes do not create empty migrations. `V76–V84`
harden privileged access; `V85` closes the last tenant-bearing table without
row-level security and indexes the entity trails; `V86` fixes definer name
resolution.
