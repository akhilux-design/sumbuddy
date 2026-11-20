export const MINUTE = 60_000;
export const HOUR = 60 * MINUTE;
export const DAY = 24 * HOUR;
const IST_OFFSET = 5 * HOUR + 30 * MINUTE;

/** 'YYYY-MM-DD' in India time (UTC+05:30). */
export function indiaDate(now: number): string {
  return new Date(now + IST_OFFSET).toISOString().slice(0, 10);
}

/** 'YYYY-MM' in India time. */
export function indiaMonth(now: number): string {
  return indiaDate(now).slice(0, 7);
}
