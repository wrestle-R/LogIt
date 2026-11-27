import { test } from "node:test";
import assert from "node:assert/strict";
import { count, change, total, dayKey, streak, goalProgress, monday, activityStats, reminderProgress, leastUsedColour } from "./model.mjs";
test("legacy days start at one and repeated logs preserve unique dates", () => {
  const next = change({ completions: ["2026-11-14"] }, "2026-11-14", 1);
  assert.equal(count(next, "2026-11-14"), 2);
  assert.equal(total(next), 2);
  assert.deepEqual(next.completions, ["2026-11-14"]);
});
test("undo preserves earlier logs and removes the day only at zero", () => {
  let h = { completions: [], dailyCounts: {} };
  h = change(change(h, "2026-11-14", 1), "2026-11-14", 1);
  assert.equal(count(change(h, "2026-11-14", -1), "2026-11-14"), 1);
  assert.deepEqual(
    change(change(h, "2026-11-14", -1), "2026-11-14", -1).completions,
    [],
  );
});
test("counts survive a legacy array overwrite", () => {
  const h = { completions: ["2026-11-13"], dailyCounts: { "2026-11-14": 3 } };
  assert.equal(total(h), 4);
  assert.equal(streak(h, "2026-11-14"), 2);
});
test("legacy dates stay unique", () => {
  assert.equal(total({ completions: ["2026-11-14", "2026-11-14"] }), 1);
});

test("three logs in a Monday to Sunday week count even on the same day", () => {
  const habit = { goal: { period: "weekly", target: 3 }, dailyCounts: { "2026-11-22": 4, "2026-11-23": 3 } };
  assert.deepEqual(goalProgress(habit, "2026-11-29"), { start: "2026-11-23", logged: 3, target: 3, period: "weekly" });
  assert.equal(goalProgress(habit, "2026-11-30").logged, 0);
  assert.equal(monday("2027-01-01"), "2026-12-28");
});

test("backdated logs and undo update goal and statistics for the original day", () => {
  const habit = { goal: { period: "daily", target: 2 }, dailyCounts: { "2026-11-25": 1 } };
  const logged = change(change(habit, "2026-11-24", 1), "2026-11-24", 1);
  assert.equal(goalProgress(logged, "2026-11-24").logged, 2);
  assert.equal(activityStats(logged, "2026-11-25").recentDays, 2);
  assert.equal(activityStats(change(logged, "2026-11-24", -1), "2026-11-25").recentLogs, 2);
  assert.equal(activityStats({}, "2026-11-25").first, undefined);
});

test("reminders skip goals that are met, not started, or still have days left", () => {
  const habit = { goal: { period: "weekly", target: 3, since: "2026-11-23" }, dailyCounts: { "2026-11-23": 2 } };
  assert.equal(reminderProgress(habit, "2026-11-22"), null);
  assert.equal(reminderProgress(habit, "2026-11-28"), null);
  assert.equal(reminderProgress(habit, "2026-11-29").logged, 2);
  assert.equal(reminderProgress(change(habit, "2026-11-23", 1), "2026-11-29"), null);
});

test("new activities take an unused colour, then a least-used colour", () => {
  assert.equal(leastUsedColour([0, 1, 3, 3], 4), 2);
  assert.equal(leastUsedColour([0, 0, 1, 2, 2, 3], 4), 1);
});

test("day keys follow the local calendar and can be evaluated in a subscriber's zone", () => {
  const instant = new Date("2026-11-25T02:35:00Z");
  assert.equal(dayKey(instant, "America/Los_Angeles"), "2026-11-24");
  assert.equal(dayKey(instant, "Asia/Tokyo"), "2026-11-25");
  assert.equal(dayKey(new Date(2026, 10, 24, 23, 30)), "2026-11-24");
});
