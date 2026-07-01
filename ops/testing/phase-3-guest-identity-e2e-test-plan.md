# Phase 3 Guest Identity E2E Test Plan

## Preconditions

- Start PostgreSQL and apply migrations through `V26`.
- Start Peak with header identity enabled only in the local test environment.
- Configure `PEAK_NIDA_MODE=simulator` only when testing online verification.
- Grant the operator the identity and reservation-guest permissions.
- Prepare an active tenant, property, room type, and vacant-clean room.

Never use real NINs or passport numbers in test environments.

## Adult Manual Verification

1. Create a guest with `dateOfBirth` and `nationality`.
2. Submit `POST .../identity-documents/manual-verification` with a recognised
   document, inspection reason, and `Idempotency-Key`.
3. Confirm the response is `VERIFIED` and the number is masked.
4. Replay the identical command with the same key and confirm `replayed=true`.
5. Confirm only one verification attempt exists.
6. Confirm neither the database, audit event, nor outbox payload contains the
   submitted number.

## Every-Occupant Check-In Gate

1. Create a reservation declaring two adults.
2. Attach both guest profiles using the reservation guest API.
3. Verify only the primary guest.
4. Request identity readiness and confirm the second guest is not ready.
5. Attempt check-in and confirm HTTP 409 with
   `GUEST_IDENTITY_INCOMPLETE`.
6. Confirm reservation remains `confirmed`, room remains vacant, and no stay
   exists.
7. Verify the second guest and confirm readiness becomes true.
8. Check in successfully.

## Minor And Guardian

1. Create one adult and one guest under 18 on the check-in date.
2. Create a reservation declaring one adult and one child.
3. Verify the adult.
4. Attach the child with relationship `CHILD`, the adult guardian ID, and
   `guardianAttestation=true`.
5. Confirm readiness succeeds.
6. Repeat without attestation and confirm the mutation is rejected.
7. Repeat with an unverified guardian and confirm readiness is false.

## Revocation And Expiry

1. Verify an adult identity and confirm readiness.
2. Revoke the document with an audited reason.
3. Confirm readiness and check-in fail immediately.
4. Submit an expired document and confirm verification is rejected.
5. Confirm legacy-unverified documents never satisfy readiness.

## Authorization And Isolation

- A user with `guests.identity.view` can read only masked metadata.
- Online verification requires `guests.identity.verify`.
- Physical fallback requires `guests.identity.manual_verify`.
- Revocation requires `guests.identity.manage`.
- Occupant changes require `reservations.guests.manage`.
- Cross-tenant and cross-property requests are denied.
- Every route resolves through `module_access_matrix`.

## NIDA Modes

- `disabled`: online verification returns a recorded unavailable outcome;
  manual verification remains available.
- `simulator`: ordinary test NIN succeeds; a number ending in `0000` is
  rejected.
- `simulator` under `prod`: startup fails.
- `cig` under `prod`: startup fails until the official adapter is implemented.

## Exit Criteria

- Unit, integration, route coverage, Modulith, migration, and full Gradle tests
  pass.
- No raw identity number is persisted or emitted.
- A reservation cannot check in until every declared occupant is compliant.
- Failed check-in leaves reservation, stay, room, billing, audit, and outbox
  state transactionally consistent.
