import createClient, { type ClientOptions } from "openapi-fetch";
import type { paths } from "./peak-hospitality-v1.js";

export type { components, operations, paths } from "./peak-hospitality-v1.js";

export type PeakHospitalityApiClient = ReturnType<
  typeof createPeakHospitalityApiClient
>;

export function createPeakHospitalityApiClient(options: ClientOptions) {
  return createClient<paths>(options);
}
