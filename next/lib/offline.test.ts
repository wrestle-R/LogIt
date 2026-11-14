import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { enqueue, queued, flush, project } from "./offline";
class MemoryStorage {
  data = new Map<string, string>();
  get length() {
    return this.data.size;
  }
  key(i: number) {
    return [...this.data.keys()][i] ?? null;
  }
  getItem(k: string) {
    return this.data.get(k) ?? null;
  }
  setItem(k: string, v: string) {
    this.data.set(k, v);
  }
  removeItem(k: string) {
    this.data.delete(k);
  }
}
let storage: MemoryStorage;
beforeEach(() => {
  storage = new MemoryStorage();
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: storage,
  });
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: { onLine: true },
  });
});
test("queue is durable and separated by account", () => {
  enqueue("a", "h", "2026-11-14", 1);
  enqueue("b", "h", "2026-11-14", 1);
  assert.equal(queued("a").length, 1);
  assert.equal(queued("b").length, 1);
  assert.equal(storage.length, 2);
});
test("offline flush leaves operations intact", async () => {
  Object.defineProperty(navigator, "onLine", {
    value: false,
    configurable: true,
  });
  enqueue("a", "h", "2026-11-14", 1);
  let calls = 0;
  await flush(
    "a",
    async () => {
      calls++;
    },
    () => {},
  );
  assert.equal(calls, 0);
  assert.equal(queued("a").length, 1);
});
test("failed sync retains operations for retry", async () => {
  enqueue("a", "h", "2026-11-14", 1);
  await assert.rejects(
    flush(
      "a",
      async () => {
        throw Error("offline");
      },
      () => {},
    ),
  );
  assert.equal(queued("a").length, 1);
  await flush(
    "a",
    async () => {},
    () => {},
  );
  assert.equal(queued("a").length, 0);
});
test("operations arriving during sync are not lost", async () => {
  enqueue("a", "h", "2026-11-14", 1);
  let applied = 0;
  await flush(
    "a",
    async () => {
      applied++;
      if (applied === 1) enqueue("a", "h", "2026-11-14", 1);
    },
    () => {},
  );
  assert.equal(applied, 2);
  assert.equal(queued("a").length, 0);
});
test("projection skips server-acknowledged operations", () => {
  const op = enqueue("a", "h", "2026-11-14", 1);
  const h = {
    id: "h",
    completions: [],
    dailyCounts: { "2026-11-14": 2 },
    logOps: { [op.id]: true },
  };
  assert.equal(project([h], queued("a"))[0].dailyCounts["2026-11-14"], 2);
});
test("projection applies queued completion and undo without duplicates", () => {
  enqueue("a", "h", "2026-11-14", 1);
  enqueue("a", "h", "2026-11-14", -1);
  const h = {
    id: "h",
    completions: [] as string[],
    dailyCounts: {} as Record<string, number>,
  };
  const result = project([h], queued("a"))[0];
  assert.equal(result.dailyCounts["2026-11-14"], 0);
  assert.deepEqual(result.completions, []);
});
