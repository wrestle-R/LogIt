import { test } from "node:test";
import assert from "node:assert/strict";
import { count, change, total, dayKey, streak } from "./model.mjs";
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
test("IST boundary and duplicate legacy dates", () => {
  assert.equal(dayKey(new Date("2026-11-13T18:29:59Z")), "2026-11-13");
  assert.equal(dayKey(new Date("2026-11-13T18:30:00Z")), "2026-11-14");
  assert.equal(total({ completions: ["2026-11-14", "2026-11-14"] }), 1);
});
