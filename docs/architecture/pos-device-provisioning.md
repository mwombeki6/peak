# POS Device Provisioning and Operational Auth

A till is a **shared terminal**. Over one dinner service it may carry twenty waiters,
one cashier and a kitchen screen, and it must survive all of them without ever
learning a secret that belongs to the hotel.

## Doctrine

1. **The till never chooses its property.** A manager does, from the PMS, under a
   strongly authenticated session. A terminal that could nominate its own hotel is a
   terminal an attacker can point anywhere.
2. **Device identity, staff identity and the cash drawer are three different things.**
   Switch Staff ends a staff session. It does not unpair the device and it does not
   close the drawer.
3. **A revoked device plus a correct PIN is still denied.** Device trust is checked
   before the PIN is looked at, so a stolen PIN on a dead terminal does nothing — and
   a lockout is not burned by someone hammering a till that is no longer trusted.
4. **PIN login is OPERATIONAL, never STRONG.** It is possession of a trusted terminal
   plus a short shared-shift secret. It is not a Keycloak login and must never be
   promoted to one to make an endpoint reachable.
5. **No shared Peak, property, PSP or Beem secret ships in the installer.** One generic
   installer, one locally generated keypair per machine.

## Provisioning

```
install → operator enters Peak URL → HTTPS validated
        → till generates an Ed25519 keypair, private key stays local
        → POST /api/v1/devices/pairing-requests  (unauthenticated, public key only)
        → six-digit code + opaque device code returned, code expires in 5 minutes
        → manager enters the code in the PMS and chooses
              property · outlet · terminal name · mode
        → POST /api/v1/tenants/{tenantId}/devices/pairing-approvals  (STRONG)
        → terminal is paired
```

The six digits are a **lookup, not a credential**. What the device holds is the opaque
device code and its private key; the pairing code only finds the waiting request,
briefly and a bounded number of times.

Pending requests carry no tenant. Inserts and lookups run through SECURITY DEFINER
functions owned by `pms_device_pairing_owner` — a NOBYPASSRLS, NOLOGIN role — so
ordinary tenant RLS cannot read a waiting code.

### What a waiting till may learn

`GET /api/v1/devices/pairing-requests/{id}` is polled by an unpaired terminal, so it is
deliberately thin. Pending, expired and denied return **only** `status`. Approved adds
the device code the till already holds, the expiry, and workspace routing
(`terminalName`, `mode`). It never returns tenant, property, outlet or guest data.

### Failed approvals are charged to the approving hotel

A wrong code cannot be attributed to the pairing it was aimed at. V120 resolved that by
counting the miss against **every** pending request at once, which let five mistyped
codes at one hotel deny every waiting terminal on the platform.

V137 moves the budget onto the tenant that submitted the wrong code
(`device_pairing_approval_misses`), which is always known because approval is a
strongly authenticated tenant-scoped route. Guessing costs the guesser their own
budget — five attempts per five minutes against a million codes — and a hotel's
waiting terminal became unreachable from outside it.

Unauthenticated pairing *creates* are separately throttled per public key.

## Staff session

```
POST /api/v1/devices/challenges   → server nonce, 2 minute life, single use
        → till signs the nonce with its device private key (Ed25519)
POST /api/v1/staff/sessions       → device code + signature + staff number + PIN
        → ops_ bearer, 8 hour life
```

Checked in this order, and the order is load-bearing:

| Step | Failure means |
|---|---|
| device is known and `active` | revoked or unknown terminal — stop, PIN never read |
| challenge unconsumed, unexpired, same device | replayed or foreign nonce |
| Ed25519 signature over the nonce | the caller does not hold the private key |
| staff number + PIN (peppered bcrypt) | wrong PIN, and only now is a lockout spent |

The response carries `tenantId`, `userId`, `propertyId`, `outletId`, `mode` and
`terminalName`. Establishing a session **revokes this device's other live sessions**, so
the previous waiter is signed out by the act of the next one signing in.

`DELETE /api/v1/staff/sessions/current` is Switch Staff / Lock: it revokes that `ops_`
session and nothing else. Pairing survives. The drawer survives.

Only the token *hash* is stored. Peak never holds an `ops_` bearer at rest, and the till
never persists one — it lives in memory for the life of the staff session.

## Authenticating as a till

REST and STOMP both take **`Authorization: Bearer ops_…` XOR the `X-Peak-*` identity
headers, never both**. On a paired terminal the ops token is the only credential in
play; the header path exists for development identity and is refused under the
production profile.

## Room charge from a till

Full `GET /rooms` and `GET /reservations` stay STRONG. A waiter posting food to a room
uses a purpose-built operational projection,
`GET /api/properties/{propertyId}/pos/room-charge-candidates`, which returns only the
stay id, room id, room number, a display name and posting eligibility — no passport,
no phone, no reservation history, no folio balance.

A match is not a posting right. The settle command re-checks in-house status, room
assignment and an open folio, so a candidate found before a guest checked out cannot be
charged after it.

V135 enforces this in the schema: the migration raises if `GET /rooms` or
`GET /reservations` is ever granted an `operational`-class permission.

## Shared till

Orders belong to backend state, not to the machine. A waiter may order against a drawer
another user opened; closing it is still the cashier's, and still requires
`actualCash`. A variance must be approved by someone other than the user who closed the
shift.
