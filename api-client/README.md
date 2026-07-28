# Peak TypeScript API Clients

Peak generates two TypeScript clients from the canonical V1 OpenAPI baseline:

| Package | API surface | OIDC realm | Consumers |
|---|---|---|---|
| `@peak/platform-api-client` | `/api/v1/platform/**` | `peak-platform` | Platform administration web |
| `@peak/hospitality-api-client` | All non-platform V1 routes | `peak-hospitality` | Unified Hospitality Web and POS |

The split follows authentication and resource-server boundaries. Tenant and
property administrator permissions remain server-side database decisions and
do not require separate SDKs.

Peak initially has three deployable frontend applications: Platform Console,
Hospitality Web and POS Desktop. Tenant administration, property
administration and departmental operations are permission-controlled
workspaces inside Hospitality Web, not separate deployments.

## Generate and verify

```sh
npm ci
npm run check
```

`npm run check` deterministically splits the canonical contract, removes
unreachable components, regenerates both clients, verifies that their path sets
are disjoint and complete, lints both contracts, type-checks them and builds
declaration artifacts. It also creates installable archives in `artifacts/`.
CI publishes those archives with the `peak-v1-contracts` build artifact so a
frontend repository can consume the exact clients produced by a backend commit.

## Use

```ts
import { createPeakHospitalityApiClient } from "@peak/hospitality-api-client";

const client = createPeakHospitalityApiClient({
  baseUrl: "https://api.example.com",
});
```

Install authentication as `openapi-fetch` middleware in the consuming
application. Browser tokens remain in memory and must come from the frontend's
assigned Keycloak realm and client. SDK availability does not imply endpoint
authorization.
