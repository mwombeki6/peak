import createClient from "openapi-fetch";
import type { paths } from "./peak-v1.js";

export type PeakV1Client = ReturnType<typeof createPeakV1Client>;

export function createPeakV1Client(baseUrl: string) {
  return createClient<paths>({ baseUrl });
}
