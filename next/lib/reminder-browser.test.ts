import assert from "node:assert/strict";
import test from "node:test";
import { checkOpenTabReminders } from "./reminder-browser";

test("open-tab reminders send once for an unmet goal and skip completed goals", async () => {
  const values = new Map<string, string>();
  const displayed: { title: string; body: string }[] = [];
  const replacements = {
    window: { Notification: {} },
    Notification: { permission: "granted" },
    localStorage: { getItem: (key: string) => values.get(key), setItem: (key: string, value: string) => values.set(key, value) },
    navigator: { serviceWorker: { ready: Promise.resolve({
      showNotification: async (title: string, data: { body: string }) => { displayed.push({ title, body: data.body }); },
    }) } },
  };
  const original = Object.fromEntries(Object.keys(replacements).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  try {
    Object.entries(replacements).forEach(([key, value]) => Object.defineProperty(globalThis, key, { value, configurable: true }));
    const incomplete = { name: "Guitar", goal: { period: "daily", target: 2, since: "2026-11-01" } };
    const complete = { ...incomplete, name: "Walk", dailyCounts: { "2026-11-24": 2 } };
    const now = new Date(2026, 10, 25, 9, 5);
    checkOpenTabReminders("test", [complete], now);
    checkOpenTabReminders("test", [incomplete, complete], now);
    checkOpenTabReminders("test", [incomplete], now);
    await Promise.resolve();
    assert.equal(displayed.length, 1);
    assert.match(displayed[0].body, /Guitar/);
    assert.doesNotMatch(displayed[0].body, /Walk/);
  } finally {
    Object.entries(original).forEach(([key, descriptor]) => {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else Reflect.deleteProperty(globalThis, key);
    });
  }
});
