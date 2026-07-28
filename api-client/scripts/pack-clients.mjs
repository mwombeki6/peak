import { mkdir, readFile, readdir, rm, stat } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(scriptDirectory, "..");
const artifactDirectory = path.join(clientRoot, "artifacts");
const configuration = await readJson("client-boundaries.json");

await rm(artifactDirectory, { recursive: true, force: true });
await mkdir(artifactDirectory, { recursive: true });

for (const boundary of configuration.boundaries) {
  const result = spawnSync(
    process.platform === "win32" ? "npm.cmd" : "npm",
    [
      "pack",
      "--workspace",
      boundary.packageName,
      "--pack-destination",
      artifactDirectory,
      "--json",
    ],
    {
      cwd: clientRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "inherit"],
    },
  );

  if (result.status !== 0) {
    throw new Error(`Failed to pack ${boundary.packageName}`);
  }

  const packed = JSON.parse(result.stdout);
  if (packed.length !== 1 || packed[0].name !== boundary.packageName) {
    throw new Error(`Unexpected npm pack result for ${boundary.packageName}`);
  }
}

const artifacts = (await readdir(artifactDirectory))
  .filter((name) => name.endsWith(".tgz"))
  .sort();

if (artifacts.length !== configuration.boundaries.length) {
  throw new Error(
    `Expected ${configuration.boundaries.length} SDK archives, found ${artifacts.length}`,
  );
}

for (const artifact of artifacts) {
  const metadata = await stat(path.join(artifactDirectory, artifact));
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error(`SDK archive is missing or empty: ${artifact}`);
  }
}

console.log(`Packed ${artifacts.join(", ")}`);

async function readJson(relativePath) {
  return JSON.parse(await readFile(path.resolve(clientRoot, relativePath), "utf8"));
}
