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

Applied migrations `V1–V69` are immutable. `V70` adds the verified property-payment
timeline index; `V69` aligned operational staff references and `V68` introduced
the Daily Control schema. Any later remediation starts at `V71`; application-only
boundary fixes do not create empty migrations.
