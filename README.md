# Peak

Peak is a modular Spring Boot property-management platform. The implemented
scope covers platform and tenant administration, property setup, reservations,
mandatory guest identity readiness, frontdesk stay transitions, folio billing,
cash and mobile-money payments, outlet POS sessions and orders, fiscal
submission, communications, realtime streams, night audit, audit, idempotency,
and transactional outbox delivery.

## Local Development

Use Podman for local infrastructure:

```bash
podman compose -f compose.yaml up -d
./gradlew test
```

Run the application with the development profile:

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

## Production Runtime Split

- `api`: serves HTTP requests and does not run the outbox worker.
- `worker`: processes outbox events and does not run Flyway.
- `migration`: runs Flyway with the migration database login.
- `bootstrap`: creates the first platform operator once, then exits.

Production profile validation fails startup when unsafe defaults are present.

## Key Security Rules

1. Keycloak JWT validation is required in production.
2. Production JWT identity is resolved through database-backed OIDC identity links.
3. Trusted header identity and trusted direct JWT identity claims are for local or controlled runtimes only.
4. Public callbacks resolve tenant/property scope from database-owned provider
   accounts; request headers and payloads never establish scope.
5. Authorization is centralized through user management and `module_access_matrix`.
6. Unsafe commands must use idempotency, audit, and outbox where side effects are involved.

## Operations

Production Podman files live in `ops/production`. Start with `ops/production/.env.example`, run the database role bootstrap, then execute the migration, API, and worker services separately.

Module-specific ownership notes live beside each module under `src/main/kotlin/com/mwombeki/peak/*/README.md`.
The complete rollout, rollback, restore, Keycloak, and smoke-test procedures are
in `ops/production/README.md`.
