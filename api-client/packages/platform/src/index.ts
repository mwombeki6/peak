import createClient, { type ClientOptions } from "openapi-fetch";
import type { paths } from "./peak-platform-v1.js";

export type { components, operations, paths } from "./peak-platform-v1.js";

export type PeakPlatformApiClient = ReturnType<typeof createPeakPlatformApiClient>;

export function createPeakPlatformApiClient(options: ClientOptions) {
  return createClient<paths>(options);
}
