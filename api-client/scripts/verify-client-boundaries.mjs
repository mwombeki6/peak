import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(scriptDirectory, "..");
const configuration = await readJson("client-boundaries.json");
const canonical = await readJson(configuration.canonicalContract);
const boundaryPaths = new Map();

for (const boundary of configuration.boundaries) {
  const contract = await readJson(boundary.contract);
  const contractPaths = new Set(Object.keys(contract.paths ?? {}));
  const generated = await readFile(
    path.resolve(clientRoot, boundary.generatedTypes),
    "utf8",
  );
  const generatedPaths = new Set(
    [...generated.matchAll(/^\s{4}"(\/api\/v1\/[^"]+)": \{$/gm)].map(
      (match) => match[1],
    ),
  );
  const packageDocument = await readJson(`packages/${boundary.id}/package.json`);
  const keycloakTemplate = await readJson(boundary.keycloakTemplate);
  const configuredInteractiveClients = new Set(
    (keycloakTemplate.clients ?? [])
      .filter((client) => client.bearerOnly !== true)
      .map((client) => client.clientId),
  );

  assert(contract["x-peak-client-boundary"] === boundary.id, `${boundary.id} metadata`);
  assert(contract["x-peak-runtime"] === boundary.runtime, `${boundary.id} runtime`);
  assert(contract["x-peak-oidc-realm"] === boundary.realm, `${boundary.id} realm`);
  assert(contract["x-peak-audience"] === boundary.audience, `${boundary.id} audience`);
  assert(packageDocument.name === boundary.packageName, `${boundary.id} package name`);
  assert(keycloakTemplate.realm === boundary.realm, `${boundary.id} Keycloak realm`);
  assertSetsEqual(
    new Set(boundary.oidcClients),
    configuredInteractiveClients,
    `${boundary.id} Keycloak frontend registrations`,
  );
  assertSetsEqual(contractPaths, generatedPaths, `${boundary.id} generated path surface`);

  for (const apiPath of contractPaths) {
    const isPlatformPath = apiPath.startsWith(configuration.platformPathPrefix);
    assert(
      boundary.pathSelection === "platform" ? isPlatformPath : !isPlatformPath,
      `${boundary.id} contains an out-of-boundary path: ${apiPath}`,
    );
  }

  boundaryPaths.set(boundary.id, contractPaths);
}

const combined = new Set();
for (const paths of boundaryPaths.values()) {
  for (const apiPath of paths) {
    assert(!combined.has(apiPath), `API path appears in multiple SDKs: ${apiPath}`);
    combined.add(apiPath);
  }
}
assertSetsEqual(
  new Set(Object.keys(canonical.paths ?? {})),
  combined,
  "split contract path partition",
);

const administrators = Object.fromEntries(
  configuration.administratorScopes.map((scope) => [scope.id, scope]),
);
const frontendApplications = configuration.frontendApplications;
const platformBoundary = configuration.boundaries.find(
  (boundary) => boundary.id === "platform",
);
const hospitalityBoundary = configuration.boundaries.find(
  (boundary) => boundary.id === "hospitality",
);
assert(
  JSON.stringify(platformBoundary.oidcClients) ===
    JSON.stringify(["peak-platform-web"]),
  "one platform frontend client",
);
assert(
  JSON.stringify(hospitalityBoundary.oidcClients) ===
    JSON.stringify(["peak-hospitality-web", "peak-pos-desktop"]),
  "unified hospitality web and POS clients",
);
assert(
  [...platformBoundary.oidcClients, ...hospitalityBoundary.oidcClients].length === 3,
  "exactly three deployable frontend clients",
);
assert(frontendApplications.length === 3, "exactly three frontend applications");
assertSetsEqual(
  new Set(["platform-console", "hospitality-web", "pos-desktop"]),
  new Set(frontendApplications.map((application) => application.id)),
  "canonical frontend applications",
);
for (const application of frontendApplications) {
  const boundary = configuration.boundaries.find(
    (candidate) => candidate.id === application.boundary,
  );
  assert(boundary !== undefined, `${application.id} SDK boundary`);
  assert(
    boundary.oidcClients.includes(application.oidcClient),
    `${application.id} OIDC client belongs to its trust boundary`,
  );
  assert(
    boundary.packageName === application.packageName,
    `${application.id} SDK follows its trust boundary`,
  );
  assert(
    boundaryPaths.get(boundary.id).has(application.bootstrapPath),
    `${application.id} bootstrap route belongs to its SDK`,
  );
}
assert(
  !hospitalityBoundary.oidcClients.includes("peak-tenant-admin"),
  "tenant administration must remain a hospitality-web workspace",
);
assert(administrators.platform.boundary === "platform", "platform admin SDK boundary");
assert(
  administrators.platform.realm === "peak-platform",
  "platform admin identity boundary",
);
assert(administrators.tenant.boundary === "hospitality", "tenant admin SDK boundary");
assert(administrators.property.boundary === "hospitality", "property admin SDK boundary");
assert(
  administrators.tenant.realm === administrators.property.realm,
  "tenant/property shared identity realm",
);
assert(
  administrators.tenant.delegates.includes("property"),
  "tenant-to-property delegation contract",
);
assert(
  !administrators.platform.delegates.includes("tenant"),
  "platform authority must not imply tenant authority",
);
assert(
  administrators.tenant.continuityPermission === "tenant.administrators.manage",
  "tenant administrator continuity permission",
);
assert(
  administrators.property.continuityPermission ===
    "tenant.properties.administrators.manage",
  "property administrator continuity permission",
);

assert(
  boundaryPaths.get("platform").has("/api/v1/platform/administrators"),
  "platform administrator API must remain in the platform SDK",
);
assert(
  boundaryPaths.get("hospitality").has("/api/v1/tenants/{tenantId}/administrators"),
  "tenant administrator API must remain in the hospitality SDK",
);
assert(
  boundaryPaths
    .get("hospitality")
    .has("/api/v1/tenants/{tenantId}/properties/{propertyId}/administrators"),
  "property administrator API must remain in the hospitality SDK",
);

console.log(
  `Verified ${combined.size} V1 paths across ${configuration.boundaries.length} isolated SDKs.`,
);

async function readJson(relativePath) {
  return JSON.parse(await readFile(path.resolve(clientRoot, relativePath), "utf8"));
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(`Client-boundary verification failed: ${message}`);
  }
}

function assertSetsEqual(expected, actual, message) {
  const missing = [...expected].filter((item) => !actual.has(item));
  const unexpected = [...actual].filter((item) => !expected.has(item));
  assert(
    missing.length === 0 && unexpected.length === 0,
    `${message}; missing=${missing.join(",")}; unexpected=${unexpected.join(",")}`,
  );
}
