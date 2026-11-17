"use client";

import Image from "next/image";
import { useEffect, useMemo, useState, useRef } from "react";
import {
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signOut,
  type User,
} from "firebase/auth";
import {
  addDoc,
  collection,
  doc,
  onSnapshot,
  query,
  runTransaction,
  serverTimestamp,
  updateDoc,
  where,
  writeBatch,
} from "firebase/firestore";
import {
  Plus,
  GripVertical,
  Moon,
  Sun,
  ArrowLeft,
  ArrowRight,
} from "lucide-react";
import { auth, db } from "@/lib/firebase";
import { count, total, dayKey, change, streak } from "@/lib/model.mjs";
import { Button } from "@/components/ui/button";
import { SkeuoButton } from "@/components/ui/skeuo-button";
import { AndroidDownload } from "@/components/android-download";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Toaster } from "@/components/ui/sonner";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Field, FieldLabel } from "@/components/ui/field";
import { toast } from "sonner";
import {
  enqueue,
  queued,
  project,
  flush,
  type PendingLog,
} from "@/lib/offline";
import {
  DndContext,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  closestCenter,
} from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  sortableKeyboardCoordinates,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

type Habit = {
  logOps?: Record<string, boolean>;
  id: string;
  name: string;
  completions: string[];
  dailyCounts?: Record<string, number>;
  order: number;
  color?: number;
  longestStreak?: number;
  userId?: string;
};
const palette = [
  "#4ade80",
  "#22d3ee",
  "#60a5fa",
  "#a78bfa",
  "#f472b6",
  "#fb7185",
  "#fb923c",
  "#facc15",
  "#a3e635",
  "#2dd4bf",
];
const names = [
  "Green",
  "Cyan",
  "Blue",
  "Violet",
  "Pink",
  "Rose",
  "Orange",
  "Yellow",
  "Lime",
  "Teal",
];
function demoHabits(): Habit[] {
  return ["Read", "Walk", "Meditate", "Practice"].map((name, i) => {
    const dailyCounts: Record<string, number> = {};
    const today = new Date(`${dayKey()}T12:00:00Z`);
    for (let d = 0; d < 112; d++) {
      const date = new Date(today);
      date.setUTCDate(today.getUTCDate() - d);
      if ((d + i) % 4)
        dailyCounts[date.toISOString().slice(0, 10)] = ((d + i) % 4) + 1;
    }
    return {
      id: `demo-${i}`,
      name,
      order: i,
      color: i,
      completions: Object.keys(dailyCounts),
      dailyCounts,
    };
  });
}
function Heatmap({
  habit,
  weeks,
  gradient,
}: {
  habit: Habit;
  weeks: number;
  gradient: boolean;
}) {
  const viewport = useRef<HTMLDivElement>(null);
  const [capacity, setCapacity] = useState(19);
  useEffect(() => {
    const node = viewport.current;
    if (!node) return;
    const observer = new ResizeObserver(([entry]) => setCapacity(Math.max(1, Math.floor((entry.contentRect.width + 3) / 17))));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  const visibleWeeks = Math.min(weeks, capacity);
  const today = new Date(`${dayKey()}T12:00:00Z`);
  return (
    <div ref={viewport} className="heatmap-fit">
    <div
      className="heatmap"
      style={{ gridTemplateColumns: `repeat(${visibleWeeks},14px)` }}
      role="img"
      aria-label={`${habit.name}, ${visibleWeeks} weeks of history`}
    >
      {Array.from({ length: visibleWeeks * 7 }, (_, i) => {
        const date = new Date(today);
        date.setUTCDate(today.getUTCDate() - (visibleWeeks * 7 - 1 - i));
        const key = date.toISOString().slice(0, 10);
        const n = count(habit, key);
        const color = palette[habit.color || 0];
        return (
          <span
            key={key}
            title={`${key}: ${n} logs`}
            style={
              n
                ? {
                    background: gradient
                      ? `linear-gradient(135deg, ${color},${palette[((habit.color || 0) + 1) % 10]})`
                      : color,
                    opacity: Math.min(1, 0.25 + n * 0.18),
                  }
                : undefined
            }
          />
        );
      })}
    </div></div>
  );
}
function Hint({
  label,
  children,
}: {
  label: string;
  children: React.ReactElement;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
function SortableActivity({
  id,
  children,
}: {
  id: string;
  children: (sort: ReturnType<typeof useSortable>) => React.ReactNode;
}) {
  const sort = useSortable({ id });
  return (
    <article
      ref={sort.setNodeRef}
      className="activity"
      style={{
        transform: CSS.Transform.toString(sort.transform),
        transition: sort.transition,
        opacity: sort.isDragging ? 0.7 : 1,
      }}
    >
      {children(sort)}
    </article>
  );
}
export default function Dashboard() {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 7 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );
  const [user, setUser] = useState<User | null>(null),
    [baseHabits, setHabits] = useState<Habit[]>([]),
    [demo, setDemo] = useState(false),
    [loaded, setLoaded] = useState(false),
    [dark, setDark] = useState(true),
    [page, setPage] = useState(0),
    [weeks, setWeeks] = useState("52"),
    [gradient, setGradient] = useState(false),
    [adding, setAdding] = useState(false),
    [authOpen, setAuthOpen] = useState(false),
    [authError, setAuthError] = useState(""),
    [name, setName] = useState(""),
    [confirm, setConfirm] = useState<Habit | null>(null),
    [busy, setBusy] = useState(false),
    [syncError, setSyncError] = useState("");

  const [pending, setPending] = useState<PendingLog[]>([]);
  const habits = useMemo(
    () => (demo ? baseHabits : project(baseHabits, pending)),
    [baseHabits, pending, demo],
  );
  useEffect(
    () =>
      onAuthStateChanged(auth, (u) => {
        setHabits([]);
        setPending([]);
        setUser(u);
      }),
    [],
  );
  useEffect(() => {
    if (!user) {
      setPending([]);
      return;
    }
    let live = true;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const update = () => {
      if (live) setPending(queued(user.uid));
    };
    const run = () => {
      update();
      if (navigator.onLine)
        void flush(user.uid, applyQueued, update).catch((e) => {
          if (live) {
            setSyncError(
              "Saved on this device. Sync pending: " +
                (e instanceof Error ? e.message : "check your connection"),
            );
            retry = setTimeout(run, 30000);
          }
        });
    };
    run();
    window.addEventListener("online", run);
    window.addEventListener("storage", run);
    return () => {
      live = false;
      if (retry) clearTimeout(retry);
      window.removeEventListener("online", run);
      window.removeEventListener("storage", run);
    };
  }, [user]);
  useEffect(() => {
    if ("serviceWorker" in navigator && process.env.NODE_ENV === "production")
      void navigator.serviceWorker
        .register("/sw.js")
        .then(async (reg) => {
          await navigator.serviceWorker.ready;
          const resources = performance
            .getEntriesByType("resource")
            .map((x) => x.name)
            .filter((x) => x.startsWith(location.origin + "/_next/static/"));
          (reg.active || reg.waiting)?.postMessage({
            type: "CACHE_ASSETS",
            resources,
          });
        })
        .catch(() => {});
  }, []);

  useEffect(() => {
    const theme = localStorage.getItem("logit-theme");
    setDark(theme !== "light");
  }, []);
  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
    localStorage.setItem("logit-theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => {
    if (demo) {
      setLoaded(true);
      return;
    }
    if (!user) {
      setHabits([]);
      setLoaded(true);
      return;
    }
    setLoaded(false);
    return onSnapshot(
      query(collection(db, "habits"), where("userId", "==", user.uid)),
      (snapshot) => {
        setHabits(
          snapshot.docs
            .map((d) => ({ ...d.data(), id: d.id }) as Habit)
            .sort((a, b) => (a.order || 0) - (b.order || 0)),
        );
        setLoaded(true);
        setSyncError("");
      },
      (error) => {
        setLoaded(true);
        setSyncError(error.message);
      },
    );
  }, [user, demo]);
  useEffect(() => {
    if (demo) localStorage.setItem("logit-demo", JSON.stringify(habits));
  }, [habits, demo]);
  const error = (e: unknown) =>
    toast.error(
      e instanceof Error ? e.message : "Something went wrong. Please retry.",
    );
  async function log(h: Habit, delta: number, day = dayKey()) {
    if (demo) {
      setHabits((old) =>
        old.map((item) => (item.id === h.id ? change(item, day, delta) : item)),
      );
      return;
    }
    if (!user) throw new Error("Sign in first");
    enqueue(user.uid, h.id, day, delta);
    setPending(queued(user.uid));
    if (navigator.onLine)
      void flush(user.uid, applyQueued, () =>
        setPending(queued(user.uid)),
      ).catch(() => setSyncError("Saved on this device. Waiting to sync."));
  }
  async function applyQueued(op: PendingLog) {
    if (auth.currentUser?.uid !== op.owner)
      throw new Error("Sign in to sync this queue");
    await runTransaction(db, async (tx) => {
      const ref = doc(db, "habits", op.habit);
      const snap = await tx.get(ref);
      if (!snap.exists()) throw new Error("Activity no longer exists");
      const current = { ...snap.data(), id: op.habit } as Habit;
      if (current.userId !== op.owner)
        throw new Error("Activity belongs to another account");
      if (current.logOps?.[op.id]) return;
      const next = change(current, op.day, op.delta);
      tx.update(ref, {
        [`dailyCounts.${op.day}`]: count(next, op.day),
        completions: next.completions,
        currentStreak: streak(next),
        longestStreak: Math.max(current.longestStreak || 0, streak(next)),
        [`logOps.${op.id}`]: true,
      });
    });
  }
  async function saveLog() {
    if (!confirm || busy) return;
    setBusy(true);
    const h = confirm;
    const day = dayKey();
    try {
      await log(h, 1, day);
      setConfirm(null);
      toast.success(`Saved ${h.name}`, {
        duration: 10000,
        action: {
          label: "Undo",
          onClick: () => {
            void log(h, -1, day)
              .then(() => toast.success("Log undone"))
              .catch(error);
          },
        },
      });
    } catch (e) {
      error(e);
    } finally {
      setBusy(false);
    }
  }
  async function add() {
    if (!name.trim() || busy) return;
    setBusy(true);
    try {
      const data = {
        name: name.trim(),
        userId: user?.uid || "demo",
        completions: [],
        dailyCounts: {},
        order: Date.now(),
        currentStreak: 0,
        longestStreak: 0,
      };
      if (demo)
        setHabits((old) => [...old, { ...data, id: crypto.randomUUID() }]);
      else
        await addDoc(collection(db, "habits"), {
          ...data,
          createdAt: serverTimestamp(),
        });
      setAdding(false);
      setName("");
    } catch (e) {
      error(e);
    } finally {
      setBusy(false);
    }
  }
  async function reorder(from: string, to: string) {
    if (from === to) return;
    const next = [...habits];
    const at = next.findIndex((h) => h.id === from);
    const target = next.findIndex((h) => h.id === to);
    if (at < 0 || target < 0) return;
    next.splice(target, 0, next.splice(at, 1)[0]);
    const ordered = next.map((h, i) => ({ ...h, order: i }));
    if (demo) {
      setHabits(ordered);
      return;
    }
    try {
      const batch = writeBatch(db);
      ordered.forEach((h) =>
        batch.update(doc(db, "habits", h.id), { order: h.order }),
      );
      await batch.commit();
    } catch (e) {
      error(e);
    }
  }
  async function color(h: Habit, value: string) {
    const n = Number(value);
    if (demo) {
      setHabits((old) =>
        old.map((x) => (x.id === h.id ? { ...x, color: n } : x)),
      );
      return;
    }
    try {
      await updateDoc(doc(db, "habits", h.id), { color: n });
    } catch (e) {
      error(e);
    }
  }
  const pages = Math.max(1, Math.ceil(habits.length / 4));
  const currentPage = Math.min(page, pages - 1);
  return (
    <TooltipProvider>
      <main className="shell">
        <header>
          <a className="wordmark" href="/">
            <Image src="/logo.svg" alt="" width={28} height={28} />
            LogIt
          </a>
          <div className="toolbar">
            <AndroidDownload />
            <Hint label={dark ? "Use light theme" : "Use dark theme"}>
              <Button
                variant="ghost"
                size="icon"
                aria-label={dark ? "Use light theme" : "Use dark theme"}
                onClick={() => setDark(!dark)}
              >
                {dark ? <Sun /> : <Moon />}
              </Button>
            </Hint>
            {(user || demo) && (
              <Button
                variant="outline"
                onClick={() => {
                  if (demo) {
                    setDemo(false);
                    setHabits([]);
                  } else void signOut(auth).catch(error);
                }}
              >
                {demo ? "Leave demo" : "Sign out"}
              </Button>
            )}
          </div>
        </header>
        {!(user || demo) && <section className="persistence" aria-labelledby="persistence-title">
          <div className="definition-title">
            <h1 id="persistence-title">persistence</h1>
            <span>/pərˈsɪstəns/</span>
          </div>
          <Badge variant="secondary">noun</Badge>
          <p>
            the fact of continuing in an opinion or course of action in spite of
            difficulty or opposition.
          </p>
          <p className="definition-quote">
            “Persistence and endurance will make you omnipotent.”
          </p>
        </section>}
        <>
          {demo && (
            <p className="demo-note">
              Local demo. Your account data stays separate.
            </p>
          )}
          {pending.length > 0 && (
            <p role="status" className="pending-note">
              {pending.length} logs saved on this device · sync pending
            </p>
          )}
          {syncError && (
            <p role="alert" className="error">
              Could not sync: {syncError}
            </p>
          )}
          <section className="activity-controls">
            <div>
              <h2>Your activities</h2>
              <p>
                {habits.length} activities ·{" "}
                {habits.reduce((s, h) => s + count(h, dayKey()), 0)} logs today
              </p>
            </div>
            <Button
              onClick={() => {
                if (user || demo) setAdding(true);
                else {
                  setAuthError("");
                  setAuthOpen(true);
                }
              }}
            >
              <Plus data-icon="inline-start" />
              Add activity
            </Button>
          </section>
          {(user || demo) && (
            <div className="filters">
              <Label htmlFor="timeframe">History</Label>
              <Select value={weeks} onValueChange={setWeeks}>
                <SelectTrigger id="timeframe" className="w-36">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {[4, 8, 16, 19, 26, 52].map((n) => (
                      <SelectItem key={n} value={String(n)}>
                        {n} weeks
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
              <Button
                variant="outline"
                aria-pressed={gradient}
                onClick={() => setGradient(!gradient)}
              >
                {gradient ? "Gradient" : "Solid"} colours
              </Button>
            </div>
          )}
          {!loaded ? (
            <p role="status">Loading your activities…</p>
          ) : habits.length === 0 ? (
            <section className="empty">
              <p>No habits yet. Create your first habit to get started!</p>
            </section>
          ) : (
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragEnd={({ active, over }) => {
                if (over) void reorder(String(active.id), String(over.id));
              }}
            >
              <SortableContext
                items={habits
                  .slice(currentPage * 4, currentPage * 4 + 4)
                  .map((h) => h.id)}
                strategy={verticalListSortingStrategy}
              >
                <div className="activities">
                  {habits
                    .slice(currentPage * 4, currentPage * 4 + 4)
                    .map((h) => (
                      <SortableActivity key={h.id} id={h.id}>
                        {(sort) => (
                          <>
                            <div className="activity-heading">
                              <div>
                                <h3>{h.name}</h3>
                                <p>
                                  {total(h)} total logs · {streak(h)} day streak
                                </p>
                              </div>
                              <div className="toolbar">
                                <Badge variant="secondary">
                                  {count(h, dayKey())} today
                                </Badge>
                                <Hint label="Drag to reorder">
                                  <Button
                                    variant="ghost"
                                    size="icon"
                                    {...sort.attributes}
                                    {...sort.listeners}
                                    style={{ touchAction: "none" }}
                                    aria-label={`Drag ${h.name} to reorder`}
                                  >
                                    <GripVertical />
                                  </Button>
                                </Hint>
                              </div>
                            </div>
                            <div className="graph-area">
                              <div className="graph-viewport">
                                <Heatmap
                                  habit={h}
                                  weeks={Number(weeks)}
                                  gradient={gradient}
                                />
                              </div>
                            </div>
                            <footer>
                              <div className="toolbar">
                                <Button variant="outline" disabled={busy || total(h) === 0}
                                  onClick={async () => {
                                    if (busy) return;
                                    setBusy(true);
                                    const day = [...new Set([...h.completions, ...Object.keys(h.dailyCounts || {})])]
                                      .filter(d => count(h, d) > 0).sort().at(-1);
                                    try { if (day) await log(h, -1, day); } catch (e) { error(e); }
                                    finally { setBusy(false); }
                                  }}>Undo</Button>
                                <SkeuoButton className="log-action" onClick={() => setConfirm(h)}><Plus /> Log today</SkeuoButton>
                              </div>
                              <Select
                                value={String(h.color || 0)}
                                onValueChange={(v) => void color(h, v)}
                              >
                                <SelectTrigger
                                  aria-label={`Colour for ${h.name}`}
                                  className="w-32"
                                >
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectGroup>
                                    {names.map((n, i) => (
                                      <SelectItem key={n} value={String(i)}>
                                        <span
                                          className="swatch"
                                          style={{ background: palette[i] }}
                                        />
                                        {n}
                                      </SelectItem>
                                    ))}
                                  </SelectGroup>
                                </SelectContent>
                              </Select>
                            </footer>
                          </>
                        )}
                      </SortableActivity>
                    ))}
                </div>
              </SortableContext>
            </DndContext>
          )}
          {pages > 1 && (
            <nav className="pagination" aria-label="Activity pages">
              <Button
                variant="outline"
                disabled={currentPage === 0}
                onClick={() => setPage(currentPage - 1)}
              >
                Previous
              </Button>
              <span>
                {currentPage + 1} / {pages}
              </span>
              <Button
                variant="outline"
                disabled={currentPage + 1 >= pages}
                onClick={() => setPage(currentPage + 1)}
              >
                Next
              </Button>
            </nav>
          )}
        </>
        <Dialog open={authOpen} onOpenChange={setAuthOpen}>
          <DialogContent className="auth-dialog">
            <DialogHeader>
              <DialogTitle>Welcome to LogIt</DialogTitle>
              <DialogDescription>
                Sign in to start tracking your habits
              </DialogDescription>
            </DialogHeader>
            {authError && (
              <p role="alert" className="error">
                {authError}
              </p>
            )}
            <Button
              variant="outline"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                setAuthError("");
                try {
                  await signInWithPopup(auth, new GoogleAuthProvider());
                  setAuthOpen(false);
                } catch (e) {
                  setAuthError(
                    e instanceof Error
                      ? e.message
                      : "Sign-in failed. Please try again.",
                  );
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? "Signing in…" : "Continue with Google"}
            </Button>
          </DialogContent>
        </Dialog>
        <Dialog
          open={adding}
          onOpenChange={(v) => {
            if (!busy) setAdding(v);
          }}
        >
          <DialogContent>
            <DialogHeader>
              <DialogTitle>New activity</DialogTitle>
              <DialogDescription>
                A name for something you want to keep showing up for.
              </DialogDescription>
            </DialogHeader>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void add();
              }}
            >
              <Field>
                <FieldLabel htmlFor="name">Activity name</FieldLabel>
                <Input
                  autoFocus
                  id="name"
                  maxLength={80}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Read, walk, practice…"
                />
              </Field>
              <DialogFooter>
                <Button
                  variant="outline"
                  type="button"
                  disabled={busy}
                  onClick={() => setAdding(false)}
                >
                  Cancel
                </Button>
                <Button type="submit" disabled={busy || !name.trim()}>
                  {busy ? "Creating…" : "Create activity"}
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
        <Dialog
          open={!!confirm}
          onOpenChange={(v) => {
            if (!v && !busy) setConfirm(null);
          }}
        >
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Log {confirm?.name}?</DialogTitle>
              <DialogDescription>
                Add one completion for today. You can undo it after logging.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => setConfirm(null)}
              >
                Cancel
              </Button>
              <Button disabled={busy} onClick={() => void saveLog()}>
                {busy ? "Saving…" : "Yes, log it"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Toaster theme={dark ? "dark" : "light"} />
        <p className="bottom-note">One day at a time.</p>
      </main>
    </TooltipProvider>
  );
}
