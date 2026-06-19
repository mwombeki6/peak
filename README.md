# Peak

Peak is a modular Spring Boot PMS platform. Phase 1 focuses on tenant governance, user authorization, public booking/payment integration, audit, idempotency, outbox reliability, and production deployment foundations.

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

Production profile validation fails startup when unsafe defaults are present.

## Key Security Rules

1. Keycloak JWT validation is required in production.
2. Production JWT identity is resolved through database-backed OIDC identity links.
3. Trusted header identity and trusted direct JWT identity claims are for local or controlled runtimes only.
4. Public tenant/property scope is resolved from URL property id and database state.
5. Authorization is centralized through user management and `module_access_matrix`.
6. Unsafe commands must use idempotency, audit, and outbox where side effects are involved.

## Operations

Production Podman files live in `ops/production`. Start with `ops/production/.env.example`, run the database role bootstrap, then execute the migration, API, and worker services separately.

Module-specific ownership notes live beside each module under `src/main/kotlin/com/mwombeki/peak/*/README.md`.
