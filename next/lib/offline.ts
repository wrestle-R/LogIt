import { change } from "./model.mjs";
export type PendingLog = {
  id: string;
  owner: string;
  habit: string;
  day: string;
  delta: number;
};
export function queued(owner: string): PendingLog[] {
  const prefix = `logit-op:${owner}:`;
  const result: (PendingLog & { createdAt: number })[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key?.startsWith(prefix)) continue;
    try {
      const x = JSON.parse(localStorage.getItem(key) || "null");
      if (
        x?.owner === owner &&
        typeof x.id === "string" &&
        typeof x.habit === "string" &&
        /^\d{4}-\d{2}-\d{2}$/.test(x.day) &&
        (x.delta === 1 || x.delta === -1)
      )
        result.push(x);
    } catch {}
  }
  return result.sort(
    (a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id),
  );
}
export function enqueue(
  owner: string,
  habit: string,
  day: string,
  delta: number,
) {
  const op = {
    id: crypto.randomUUID(),
    owner,
    habit,
    day,
    delta,
    createdAt: Date.now() + (performance.now() % 1),
  };
  localStorage.setItem(`logit-op:${owner}:${op.id}`, JSON.stringify(op));
  return op;
}
export function project<
  T extends { id: string; logOps?: Record<string, boolean> },
>(base: T[], ops: PendingLog[]): T[] {
  return base.map((h) =>
    ops
      .filter((op) => op.habit === h.id && !h.logOps?.[op.id])
      .reduce((current, op) => change(current, op.day, op.delta), h),
  );
}
const running = new Map<string, Promise<void>>();
export function flush(
  owner: string,
  apply: (op: PendingLog) => Promise<void>,
  updated: () => void,
): Promise<void> {
  const active = running.get(owner);
  if (active) return active;
  const work = (async () => {
    while (navigator.onLine) {
      const op = queued(owner)[0];
      if (!op) break;
      await apply(op);
      localStorage.removeItem(`logit-op:${owner}:${op.id}`);
      updated();
    }
  })().finally(() => running.delete(owner));
  running.set(owner, work);
  return work;
}
