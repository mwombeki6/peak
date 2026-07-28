import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(scriptDirectory, "..");
const configuration = JSON.parse(
  await readFile(path.join(clientRoot, "client-boundaries.json"), "utf8"),
);
const canonicalPath = path.resolve(clientRoot, configuration.canonicalContract);
const canonical = JSON.parse(await readFile(canonicalPath, "utf8"));
const canonicalPaths = Object.keys(canonical.paths ?? {});

if (canonicalPaths.length === 0) {
  throw new Error(`Canonical OpenAPI contract has no paths: ${canonicalPath}`);
}

const emittedPathSets = new Map();

for (const boundary of configuration.boundaries) {
  const selectedEntries = Object.entries(canonical.paths).filter(([apiPath]) =>
    boundary.pathSelection === "platform"
      ? apiPath.startsWith(configuration.platformPathPrefix)
      : !apiPath.startsWith(configuration.platformPathPrefix),
  );

  if (selectedEntries.length === 0) {
    throw new Error(`Boundary ${boundary.id} selected no OpenAPI paths`);
  }

  const document = structuredClone(canonical);
  document.info = {
    ...document.info,
    title: boundary.title,
    description: boundary.description,
  };
  document.paths = Object.fromEntries(selectedEntries);
  document["x-peak-client-boundary"] = boundary.id;
  document["x-peak-runtime"] = boundary.runtime;
  document["x-peak-oidc-realm"] = boundary.realm;
  document["x-peak-audience"] = boundary.audience;
  document.components = reachableComponents(document, canonical.components ?? {});

  if (Array.isArray(document.tags)) {
    const usedTags = new Set(
      Object.values(document.paths)
        .flatMap((pathItem) => Object.values(pathItem))
        .flatMap((operation) =>
          operation && typeof operation === "object" && Array.isArray(operation.tags)
            ? operation.tags
            : [],
        ),
    );
    document.tags = document.tags.filter((tag) => usedTags.has(tag.name));
  }

  assertReferencesResolve(document, boundary.id);

  const outputPath = path.resolve(clientRoot, boundary.contract);
  await mkdir(path.dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${JSON.stringify(document, null, 2)}\n`);
  emittedPathSets.set(boundary.id, new Set(Object.keys(document.paths)));
}

assertExactPartition(canonicalPaths, emittedPathSets);

function reachableComponents(document, canonicalComponents) {
  const required = new Map();
  const rootWithoutComponents = { ...document };
  delete rootWithoutComponents.components;
  collectComponentReferences(rootWithoutComponents, required);

  let changed = true;
  while (changed) {
    changed = false;
    for (const [section, names] of required) {
      for (const name of [...names]) {
        const component = canonicalComponents[section]?.[name];
        if (component === undefined) {
          throw new Error(`Missing OpenAPI component #/components/${section}/${name}`);
        }
        const before = referenceCount(required);
        collectComponentReferences(component, required);
        changed ||= referenceCount(required) !== before;
      }
    }
  }

  const selected = {};
  for (const [section, names] of required) {
    selected[section] = {};
    for (const name of names) {
      selected[section][name] = canonicalComponents[section][name];
    }
  }

  for (const securityName of collectSecuritySchemeNames(rootWithoutComponents)) {
    const scheme = canonicalComponents.securitySchemes?.[securityName];
    if (scheme === undefined) {
      throw new Error(`Missing OpenAPI security scheme ${securityName}`);
    }
    selected.securitySchemes ??= {};
    selected.securitySchemes[securityName] = scheme;
  }

  return selected;
}

function collectComponentReferences(value, required) {
  if (Array.isArray(value)) {
    value.forEach((item) => collectComponentReferences(item, required));
    return;
  }
  if (value === null || typeof value !== "object") {
    return;
  }

  if (typeof value.$ref === "string" && value.$ref.startsWith("#/components/")) {
    const [, , rawSection, rawName] = value.$ref.split("/");
    if (!rawSection || !rawName) {
      throw new Error(`Unsupported component reference ${value.$ref}`);
    }
    const section = decodeJsonPointer(rawSection);
    const name = decodeJsonPointer(rawName);
    required.set(section, required.get(section) ?? new Set());
    required.get(section).add(name);
  }

  Object.values(value).forEach((item) => collectComponentReferences(item, required));
}

function collectSecuritySchemeNames(value, names = new Set()) {
  if (Array.isArray(value)) {
    value.forEach((item) => collectSecuritySchemeNames(item, names));
    return names;
  }
  if (value === null || typeof value !== "object") {
    return names;
  }

  if (Array.isArray(value.security)) {
    for (const requirement of value.security) {
      Object.keys(requirement).forEach((name) => names.add(name));
    }
  }
  Object.values(value).forEach((item) => collectSecuritySchemeNames(item, names));
  return names;
}

function assertReferencesResolve(document, boundaryId) {
  const references = new Map();
  collectComponentReferences(document, references);
  for (const [section, names] of references) {
    for (const name of names) {
      if (document.components?.[section]?.[name] === undefined) {
        throw new Error(
          `${boundaryId} contract has unresolved reference #/components/${section}/${name}`,
        );
      }
    }
  }
}

function assertExactPartition(canonicalPathNames, pathSets) {
  const owners = new Map();
  for (const [boundary, paths] of pathSets) {
    for (const apiPath of paths) {
      owners.set(apiPath, [...(owners.get(apiPath) ?? []), boundary]);
    }
  }

  const missing = canonicalPathNames.filter((apiPath) => !owners.has(apiPath));
  const duplicated = [...owners].filter(([, boundaries]) => boundaries.length !== 1);
  if (missing.length > 0 || duplicated.length > 0) {
    throw new Error(
      `Client contracts must exactly partition V1 paths; missing=${missing.join(",")}; ` +
        `duplicated=${duplicated.map(([apiPath]) => apiPath).join(",")}`,
    );
  }
}

function referenceCount(required) {
  return [...required.values()].reduce((total, names) => total + names.size, 0);
}

function decodeJsonPointer(value) {
  return value.replaceAll("~1", "/").replaceAll("~0", "~");
}
