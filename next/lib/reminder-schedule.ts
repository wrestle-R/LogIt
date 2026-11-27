import { dayKey, shiftDay } from "./model.mjs";

const hourFormatters = new Map<string, Intl.DateTimeFormat>();
const legacyTimeZone = "Asia/Kolkata";

export function dueReminder(now: Date, savedTimeZone?: string | null) {
  const timeZone = savedTimeZone || legacyTimeZone;
  let formatter = hourFormatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en", { timeZone, hour: "numeric", hourCycle: "h23" });
    if (hourFormatters.size >= 64) hourFormatters.clear();
    hourFormatters.set(timeZone, formatter);
  }
  const hour = Number(formatter.formatToParts(now).find(part => part.type === "hour")?.value);
  if (hour >= 9 && hour < 12) return { day: shiftDay(dayKey(now, timeZone), -1), slot: "lastMissed" } as const;
  if (hour >= 20) return { day: dayKey(now, timeZone), slot: "lastEvening" } as const;
  return null;
}
