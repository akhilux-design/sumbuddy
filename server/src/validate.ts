// Hand-written validators (docs/server.md 4.6, 4.7). Each throws `400 bad_request` with the JSON path in `field`.
import { badRequest } from "./http";
import { isUuid } from "./ids";
import { DAY, indiaDate } from "./time";

export type Json = Record<string, unknown>;

// C0 and C1 controls, and the bidirectional overrides and isolates (U+202A-U+202E, U+2066-U+2069).
const FORBIDDEN = /[\u0000-\u001f\u007f-\u009f‪-‮⁦-⁩]/;
const PHONE_RE = /^\+[1-9][0-9]{7,14}$/;
const UPI_RE = /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$/;
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

export const CATEGORIES = ["FOOD", "GROCERIES", "TRAVEL", "RENT_AND_BILLS", "SHOPPING", "FUN", "OTHER"] as const;
export const SPLIT_METHODS = ["EQUAL", "AMOUNTS", "PERCENT", "SHARES"] as const;
export const SETTLE_METHODS = ["UPI", "CASH", "OTHER"] as const;
export const MAX_AMOUNT = 1_000_000_000;

export function object(v: unknown, field = "body"): Json {
  if (v === null || typeof v !== "object" || Array.isArray(v)) throw badRequest(field, "Expected a JSON object.");
  return v as Json;
}

/** Trimmed text of `min`..`max` characters (code points) with no control or bidirectional override characters. */
export function text(v: unknown, field: string, min: number, max: number): string {
  if (typeof v !== "string") throw badRequest(field, "Expected text.");
  const t = v.trim();
  const len = [...t].length;
  if (len < min || len > max) throw badRequest(field, "Text length out of range.");
  if (FORBIDDEN.test(t)) throw badRequest(field, "Text holds characters that are not allowed.");
  return t;
}

/** Strips what `text` rejects; for names taken from a Google ID token. */
export function cleanName(v: unknown, fallback: string): string {
  if (typeof v !== "string") return fallback;
  const t = [...v.replace(new RegExp(FORBIDDEN.source, "g"), "").trim()].slice(0, 60).join("").trim();
  return t.length > 0 ? t : fallback;
}

export function uuid(v: unknown, field: string): string {
  if (!isUuid(v)) throw badRequest(field, "Expected a lowercase UUID.");
  return v;
}

export function phone(v: unknown, field: string): string {
  if (typeof v !== "string" || !PHONE_RE.test(v)) throw badRequest(field, "Expected an E.164 phone number.");
  return v;
}

export function optionalPhone(v: unknown, field: string): string | null {
  return v === null || v === undefined ? null : phone(v, field);
}

/**
 * A UPI ID (docs/server.md 4.5): spaces at both ends are dropped, then the rest must match UPI_RE. Case is kept as
 * typed. The app applies the same rule and sends null, never "", for an empty field.
 */
export function upiId(v: unknown, field: string): string {
  if (typeof v !== "string") throw badRequest(field, "Expected a UPI ID.");
  const t = v.trim();
  if (!UPI_RE.test(t)) throw badRequest(field, "Expected a UPI ID.");
  return t;
}

export function int(v: unknown, field: string, min: number, max: number): number {
  if (typeof v !== "number" || !Number.isSafeInteger(v) || v < min || v > max) {
    throw badRequest(field, "Integer out of range.");
  }
  return v;
}

export function bool(v: unknown, field: string): boolean {
  if (typeof v !== "boolean") throw badRequest(field, "Expected true or false.");
  return v;
}

export function oneOf<T extends string>(v: unknown, field: string, values: readonly T[]): T {
  if (typeof v !== "string" || !(values as readonly string[]).includes(v)) throw badRequest(field, "Unknown value.");
  return v as T;
}

export function amount(v: unknown, field: string): number {
  return int(v, field, 1, MAX_AMOUNT);
}

/** A real date from 2000-01-01 to tomorrow in India time. */
export function date(v: unknown, field: string, now: number): string {
  if (typeof v !== "string") throw badRequest(field, "Expected a date.");
  const m = DATE_RE.exec(v);
  if (!m) throw badRequest(field, "Expected YYYY-MM-DD.");
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const dt = new Date(Date.UTC(y, mo - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) {
    throw badRequest(field, "Not a real date.");
  }
  if (v < "2000-01-01" || v > indiaDate(now + DAY)) throw badRequest(field, "Date out of range.");
  return v;
}

export function array(v: unknown, field: string, min: number, max: number): unknown[] {
  if (!Array.isArray(v) || v.length < min || v.length > max) throw badRequest(field, "Array length out of range.");
  return v;
}
