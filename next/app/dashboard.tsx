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
} from "lucide-react";
import { checkOpenTabReminders, setBrowserReminders, watchPushReminderTimeZone } from "@/lib/reminder-browser";
import { auth, db } from "@/lib/firebase";
import { count, total, dayKey, change, streak, shiftDay, monday, goalProgress, leastUsedColour } from "@/lib/model.mjs";
import { Introduction } from "@/components/introduction";
import { Statistics } from "@/components/statistics";
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

type Goal = { period: "daily" | "weekly"; target: number; since: string };
type Habit = {
  goal?: Goal | null;
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
  onLog,
}: {
  habit: Habit;
  weeks: number;
  gradient: boolean;
  onLog: () => void;
}) {
  const viewport = useRef<HTMLDivElement>(null);
  const press = useRef<{ x: number; y: number; id: number; timer: number } | null>(null);
  const lastTouch = useRef<{ time: number; x: number; y: number } | null>(null);
  function cancelPress() {
    if (press.current) window.clearTimeout(press.current.timer);
    press.current = null;
  }
  const [capacity, setCapacity] = useState(19);
  useEffect(() => {
    const node = viewport.current;
    if (!node) return;
    const observer = new ResizeObserver(([entry]) => setCapacity(Math.max(1, Math.floor((entry.contentRect.width + 3) / 17))));
    observer.observe(node);
    return () => { observer.disconnect(); cancelPress(); };
  }, []);
  const visibleWeeks = Math.min(weeks, capacity);
  const today = dayKey();
  const start = shiftDay(monday(today), -(visibleWeeks - 1) * 7);
  const columns = `repeat(${visibleWeeks},14px)`;
  const binary = Object.keys(habit.logOps ?? {}).some(id => id.startsWith("auto-"));
  return (
    <div
      ref={viewport}
      className="heatmap-fit"
      role="button"
      tabIndex={0}
      onDoubleClick={() => {
        if (Date.now() - (lastTouch.current?.time ?? 0) > 500) onLog();
      }}
      onPointerDown={event => {
        if (!event.isPrimary || event.button !== 0) return;
        cancelPress();
        press.current = {
          id: event.pointerId, x: event.clientX, y: event.clientY,
          timer: window.setTimeout(() => {
            cancelPress();
            lastTouch.current = null;
            onLog();
          }, 550),
        };
      }}
      onPointerMove={event => {
        const gesture = press.current;
        if (gesture && Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) > 10) cancelPress();
      }}
      onPointerUp={event => {
        const gesture = press.current;
        if (!gesture || gesture.id !== event.pointerId) return;
        cancelPress();
        if (event.pointerType !== "touch") return;
        const time = Date.now();
        const previous = lastTouch.current;
        lastTouch.current = {time, x: event.clientX, y: event.clientY};
        if (previous && time - previous.time < 350 &&
            Math.hypot(previous.x - event.clientX, previous.y - event.clientY) <= 24) onLog();
      }}
      onPointerLeave={cancelPress}
      onPointerCancel={cancelPress}
      onContextMenu={event => event.preventDefault()}
      onKeyDown={event => {
        if (event.repeat || (event.key !== "Enter" && event.key !== " ")) return;
        event.preventDefault();
        onLog();
      }}
      aria-label={`Log ${habit.name}. ${habit.name}, ${visibleWeeks} weeks of history. ${count(habit, today)} today, ${count(habit, shiftDay(today, -1))} yesterday.`}
    >
      <div className="heatmap" style={{ gridTemplateColumns: columns }}>
        {Array.from({ length: visibleWeeks * 7 }, (_, i) => {
          const key = shiftDay(start, i);
          if (key > today) return <span key={key} style={{visibility: "hidden"}} />;
          const n = count(habit, key);
          const color = palette[habit.color || 0];
          return (
            <span
              key={key}
              title={`${key}: ${n} logs`}
              style={
                n
                  ? {
                      background: gradient && !binary
                        ? `linear-gradient(135deg, ${color},${palette[((habit.color || 0) + 1) % 10]})`
                        : color,
                      opacity: binary ? 1 : Math.min(1, 0.25 + n * 0.18),
                    }
                  : undefined
              }
            />
          );
        })}
      </div>
    </div>
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
    [weeks, setWeeks] = useState("52"),
    [gradient, setGradient] = useState(false),
    [adding, setAdding] = useState(false),
    [authOpen, setAuthOpen] = useState(false),
    [authError, setAuthError] = useState(""),
    [name, setName] = useState(""),
    [confirm, setConfirm] = useState<Habit | null>(null),
    [busy, setBusy] = useState(false),
    [syncError, setSyncError] = useState("");

  const [logDay, setLogDay] = useState("");
  const [today, setToday] = useState(() => dayKey());
  const [deleteTarget, setDeleteTarget] = useState<Habit | null>(null);
  const activityEditorTrigger = useRef<HTMLButtonElement | null>(null);
  const addActivityTrigger = useRef<HTMLButtonElement>(null);
  const deleting = useRef(new Map<string, Set<string>>());
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const [editing, setEditing] = useState<Habit | null>(null);
  const [period, setPeriod] = useState<"none" | Goal["period"]>("none");
  const [target, setTarget] = useState("1");
  const [statsOpen, setStatsOpen] = useState(false);
  const [introductionOwner, setIntroductionOwner] = useState<string | null>(null);
  const introductionKey = user ? "logit-introduction:" + user.uid : null;
  useEffect(() => {
    if (!introductionKey || !loaded) return;
    let completed = false;
    try { completed = localStorage.getItem(introductionKey) === "done"; } catch {}
    if (!completed) setIntroductionOwner(introductionKey);
  }, [introductionKey, loaded]);

  function finishIntroduction() {
    if (introductionKey) {
      try { localStorage.setItem(introductionKey, "done"); } catch {}
    }
    setIntroductionOwner(null);
  }
  const [remindersOpen, setRemindersOpen] = useState(false);
  const [remindersOn, setRemindersOn] = useState(false);
  const [remindersBusy, setRemindersBusy] = useState(false);
  const [reminderMode, setReminderMode] = useState("off");
  const [pastLog, setPastLog] = useState(false);
  type LogHistory = { undo: Record<string, string[]>; redo: Record<string, { day: string; id: string }[]> };
  const [history, setHistory] = useState<LogHistory>({ undo: {}, redo: {} });
  const undoKey = demo ? "logit-undo:demo" : user ? "logit-undo:" + user.uid : null;
  useEffect(() => {
    try {
      const saved = undoKey ? JSON.parse(localStorage.getItem(undoKey) || "{}") : {};
      setHistory(saved.undo && saved.redo ? saved : { undo: saved, redo: {} });
    } catch {
      setHistory({ undo: {}, redo: {} });
    }
  }, [undoKey]);
  function rememberHistory(next: LogHistory) {
    setHistory(next);
    if (undoKey) {
      try { localStorage.setItem(undoKey, JSON.stringify(next)); } catch {}
    }
  }
  const validTarget = period === "none" || /^\d+$/.test(target) && Number(target) >= 1 && Number(target) <= 999;
  function openLog(habit: Habit, past: boolean, startToday = false) {
    toast.dismiss("log-feedback");
    setLogDay(past && !startToday ? shiftDay(dayKey(), -1) : dayKey());
    setPastLog(past);
    setConfirm(habit);
  }
  function openGoal(habit: Habit | null) {
    toast.dismiss("log-feedback");
    setEditing(habit);
    setName(habit?.name || "");
    setPeriod(habit?.goal?.period || "none");
    setTarget(String(habit?.goal?.target || 1));
    setAdding(true);
  }

  const [pending, setPending] = useState<PendingLog[]>([]);
  const habits = useMemo(
    () => (demo ? baseHabits : project(baseHabits, pending)).filter(habit => !hidden.has(habit.id)),
    [baseHabits, pending, demo, hidden],
  );
  useEffect(() => {
    const refresh = () => setToday(dayKey());
    refresh();
    const timer = window.setInterval(refresh, 1_000);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, []);
  useEffect(
    () =>
      onAuthStateChanged(auth, (u) => {
        setHabits([]);
        setPending([]);
        setHistory({ undo: {}, redo: {} });
        setHidden(new Set(u ? deleting.current.get(u.uid) : undefined));
        setDeleteTarget(null);
        setAdding(false);
        setConfirm(null);
        setUser(u);
      }),
    [],
  );
  useEffect(() => {
    const mode = user ? localStorage.getItem("logit-reminders:" + user.uid) : null;
    setReminderMode(mode || "off");
    setRemindersOn(mode === "on" || mode === "push" || mode === "open-tab");
  }, [user]);
  useEffect(() => {
    if (!user) return;
    return watchPushReminderTimeZone(user);
  }, [user, reminderMode]);
  useEffect(() => {
    if (!user || !loaded || syncError || reminderMode !== "open-tab") return;
    const check = () => { if (navigator.onLine) checkOpenTabReminders(user.uid, habits); };
    check();
    const timer = window.setInterval(check, 60_000);
    return () => window.clearInterval(timer);
  }, [user, loaded, habits, syncError, reminderMode]);
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
    if (demo) localStorage.setItem("logit-demo", JSON.stringify(baseHabits));
  }, [baseHabits, demo]);
  const error = (e: unknown) =>
    toast.error(
      e instanceof Error ? e.message : "Something went wrong. Please retry.",
    );
  async function log(h: Habit, delta: number, day = dayKey(), operationId?: string) {
    if (demo) {
      setHabits((old) =>
        old.map((item) => (item.id === h.id ? change(item, day, delta) : item)),
      );
      return;
    }
    if (!user) throw new Error("Sign in first");
    if (deleting.current.get(user.uid)?.has(h.id)) throw new Error("This activity is being deleted.");
    enqueue(user.uid, h.id, day, delta, operationId);
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
      if (auth.currentUser?.uid !== op.owner)
        throw new Error("Sign in to sync this queue");
      if (!snap.exists()) return;
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
    const day = logDay;
    if (!day || day > dayKey()) { setBusy(false); return; }
    try {
      await log(h, 1, day);
      rememberHistory({
        undo: {...history.undo, [h.id]: [...(history.undo[h.id] || []), day]},
        redo: {...history.redo, [h.id]: []},
      });
      setConfirm(null);
      toast.success(`Saved ${h.name} for ${day === dayKey() ? "today" : day}`, { id: "log-feedback" });
    } catch (e) {
      error(e);
    } finally {
      setBusy(false);
    }
  }
  async function moveLogHistory(habit: Habit, direction: "undo" | "redo") {
    if (busy) return;
    const undo = history.undo[habit.id] || [];
    const redo = history.redo[habit.id] || [];
    const reapply = redo.at(-1);
    const day = direction === "undo" ? undo.at(-1) : reapply?.day;
    if (!day || (direction === "undo" && count(habit, day) < 1)) return;
    setBusy(true);
    try {
      const isUndo = direction === "undo";
      await log(habit, isUndo ? -1 : 1, day, isUndo ? undefined : reapply?.id);
      rememberHistory({
        undo: { ...history.undo, [habit.id]: isUndo ? undo.slice(0, -1) : [...undo, day] },
        redo: { ...history.redo, [habit.id]: isUndo ? [...redo, { day, id: crypto.randomUUID() }] : redo.slice(0, -1) },
      });
      toast.success(isUndo ? "Log undone" : "Log redone", { id: "log-feedback" });
    } catch (e) {
      error(e);
    } finally {
      setBusy(false);
    }
  }
  async function add() {
    if (!name.trim() || !validTarget || busy) return;
    setBusy(true);
    try {
      const goal = period === "none" ? null : editing?.goal?.period === period && editing.goal.target === Number(target) ? editing.goal : { period, target: Number(target), since: dayKey() };
      if (editing) {
        const id = editing.id;
        if (demo) setHabits(old => old.map(h => h.id === id ? {...h, name: name.trim(), goal} : h));
        else {
          if (!user || !navigator.onLine) throw new Error("Connect to the internet to save activity changes.");
          const ref = doc(db, "habits", id);
          await runTransaction(db, async transaction => {
            const snapshot = await transaction.get(ref);
            if (!snapshot.exists() || snapshot.data().userId !== user.uid || auth.currentUser?.uid !== user.uid) {
              throw new Error("This activity is no longer available.");
            }
            transaction.update(ref, { name: name.trim(), goal });
          });
        }
        setAdding(false);
        if (goal && user && !localStorage.getItem("logit-reminders:" + user.uid)) setRemindersOpen(true);
        return;
      }
      const data = {
        goal,
        name: name.trim(),
        userId: user?.uid || "demo",
        completions: [],
        dailyCounts: {},
        order: Date.now(),
        color: leastUsedColour(habits.map((habit) => habit.color ?? 0), palette.length),
        currentStreak: 0,
        longestStreak: 0,
      };
      if (demo)
        setHabits((old) => [...old, { ...data, id: crypto.randomUUID() }]);
      else {
        if (!user || !navigator.onLine) throw new Error("Connect to the internet to create an activity.");
        if (auth.currentUser?.uid !== user.uid) throw new Error("Your account changed.");
        await addDoc(collection(db, "habits"), { ...data, createdAt: serverTimestamp() });
      }
      setAdding(false);
      if (goal && user && !localStorage.getItem("logit-reminders:" + user.uid)) setRemindersOpen(true);
      setName("");
    } catch (e) {
      error(e);
    } finally {
      setBusy(false);
    }
  }
  async function deleteActivity(habit: Habit) {
    const owner = user?.uid;
    if (!demo && !owner) return;
    setDeleteTarget(null);
    if (demo) {
      setHabits(current => current.filter(item => item.id !== habit.id));
      return;
    }
    if (!owner) return;
    const operations = deleting.current.get(owner) ?? new Set<string>();
    if (operations.has(habit.id)) return;
    operations.add(habit.id);
    deleting.current.set(owner, operations);
    setHidden(new Set(operations));
    const pendingToast = toast.loading("Deleting activity…");
    try {
      if (!navigator.onLine) throw new Error("Offline");
      const ref = doc(db, "habits", habit.id);
      await runTransaction(db, async transaction => {
        const snapshot = await transaction.get(ref);
        if (auth.currentUser?.uid !== owner) throw new Error("Your account changed.");
        if (!snapshot.exists()) return;
        if (snapshot.data().userId !== owner) throw new Error("This activity is no longer available.");
        transaction.delete(ref);
      });
      if (auth.currentUser?.uid === owner) {
        setHabits(current => current.filter(item => item.id !== habit.id));
        toast.success("Activity deleted", { id: pendingToast });
      }
    } catch {
      if (auth.currentUser?.uid === owner) {
        toast.error("Couldn’t delete the activity. Check your connection and try again.", { id: pendingToast });
      }
    } finally {
      if (auth.currentUser?.uid !== owner) toast.dismiss(pendingToast);
      operations.delete(habit.id);
      if (!operations.size) deleting.current.delete(owner);
      if (auth.currentUser?.uid === owner) setHidden(new Set(operations));
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
  if (statsOpen) return <Statistics habits={habits} palette={palette} onBack={() => setStatsOpen(false)} />;
  const definition = (
    <section className="persistence" aria-labelledby="persistence-title">
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
    </section>
  );
  return (
    <TooltipProvider>
      <main className="shell">
        <header>
          <a className="wordmark" href="/">
            <Image src="/logo.svg" alt="" width={28} height={28} />
            LogIt
          </a>
          <div className="toolbar">
            <span data-intro="android" className="inline-flex"><AndroidDownload /></span>
            <Hint label={dark ? "Use light theme" : "Use dark theme"}>
              <Button
                variant="ghost"
                size="icon"
                className="theme-toggle"
                aria-label={dark ? "Use light theme" : "Use dark theme"}
                onClick={() => setDark(!dark)}
              >
                <span data-active={dark}><Moon /></span><span data-active={!dark}><Sun /></span>
              </Button>
            </Hint>
            {(user || demo) && (
              <Button
                variant="outline"
                onClick={() => {
                  if (demo) {
                    setDemo(false);
                    setHabits([]);
                  } else if (user) void (async () => {
                    if (remindersOn) {
                      await setBrowserReminders(user, false);
                      localStorage.removeItem("logit-reminders:" + user.uid);
                    }
                    await signOut(auth);
                  })().catch(error);
                }}
              >
                {demo ? "Leave demo" : "Sign out"}
              </Button>
            )}
          </div>
        </header>
        {!(user || demo) && definition}
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
            <div className="sr-only">
              <h2>Your activities</h2>
              <p>
                {habits.length} activities ·{" "}
                {habits.reduce((s, h) => s + count(h, today), 0)} logs today
              </p>
            </div>
            <Button
              ref={addActivityTrigger}
              data-intro="add"
              onClick={() => {
                if (user || demo) openGoal(null);
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
              <Button variant="outline" onClick={() => setStatsOpen(true)}>View statistics</Button>
              {user && <Button data-intro="reminders" variant="outline" onClick={() => setRemindersOpen(true)}>Goal reminders</Button>}
              {user && <Button variant="outline" onClick={() => setIntroductionOwner(introductionKey)}>Introduction</Button>}
              <div className="inline-flex items-center gap-2">
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
              </div>
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
              <p>No activities yet. Add an activity to get started.</p>
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
                items={habits.map((h) => h.id)}
                strategy={verticalListSortingStrategy}
              >
                <div className="activities">
                  {habits.map((h) => (
                      <SortableActivity key={h.id} id={h.id}>
                        {(sort) => (
                          <>
                            <div className="activity-heading">
                              <div>
                                <h3>{h.name}</h3>
                                {(() => {
                                  const progress = goalProgress(h, today);
                                  return progress && <p>{progress.logged} of {progress.target} {progress.period === "daily" ? "today" : "this week"}</p>;
                                })()}
                                <Button data-intro="goal" variant="outline" size="sm" className="px-2" onClick={event => {
                                  activityEditorTrigger.current = event.currentTarget;
                                  openGoal(h);
                                }} aria-label={`Edit ${h.name}`}>
                                  Edit
                                </Button>
                              </div>
                              <div className="toolbar">
                                <Badge variant="secondary">
                                  {count(h, today)} today
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
                                  onLog={() => openLog(h, true, true)}
                                />
                              </div>
                            </div>
                            <footer>
                              <div className="toolbar">
                                <Button variant="outline" size="sm" onClick={() => openLog(h, true)}>Add for yesterday</Button>
                                <div className="log-history" data-intro="log-history">
                                  <Button variant="outline" disabled={busy || count(h, history.undo[h.id]?.at(-1) || "") < 1}
                                    onClick={() => void moveLogHistory(h, "undo")}>Undo</Button>
                                  <Button variant="outline" disabled={busy || !history.redo[h.id]?.length}
                                    onClick={() => void moveLogHistory(h, "redo")}>Redo</Button>
                                </div>
                                <SkeuoButton className={`log-action ${count(h, today) > 0 ? "is-logged" : ""}`} onClick={() => openLog(h, false)}><Plus /> {count(h, today) > 0 ? "Log again" : "Log today"}</SkeuoButton>
                              </div>
                              <div className="activity-details">
                                <p className="activity-stats"><span>Current: {streak(h)} days</span><span>Best: {Math.max(h.longestStreak || 0, streak(h))} days</span><span>Total: {total(h)} logs</span></p>
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
                              </div>
                            </footer>
                          </>
                        )}
                      </SortableActivity>
                    ))}
                </div>
              </SortableContext>
            </DndContext>
          )}
        </>
        {(user || demo) && definition}
        {introductionKey && introductionOwner === introductionKey && (
          <Introduction key={introductionKey} onDone={finishIntroduction} />
        )}
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
              <DialogTitle>{editing ? "Edit activity" : "New activity"}</DialogTitle>
              <DialogDescription>
                A goal is optional. You can change it anytime.
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
                  placeholder="e.g. Read"
                />
              </Field>
              <Field className="mt-4">
                <FieldLabel htmlFor="goal-period">Goal</FieldLabel>
                <select id="goal-period" className="logit-select" value={period} onChange={e => setPeriod(e.target.value as typeof period)}>
                  <option value="none">No goal</option><option value="daily">Every day</option><option value="weekly">Every week</option>
                </select>
              </Field>
              {period !== "none" && <Field className="mt-4">
                <FieldLabel htmlFor="goal-target">Logs per {period === "daily" ? "day" : "week"}</FieldLabel>
                <Input id="goal-target" type="number" min={1} max={999} value={target} onChange={e => setTarget(e.target.value)} required />
              </Field>}
              <DialogFooter>
                <Button
                  variant="outline"
                  type="button"
                  disabled={busy}
                  onClick={() => setAdding(false)}
                >
                  Cancel
                </Button>
                <Button type="submit" disabled={busy || !name.trim() || !validTarget}>
                  {busy ? "Saving…" : editing ? "Save changes" : "Create activity"}
                </Button>
              </DialogFooter>
            </form>
            {editing && <Button variant="ghost" className="justify-self-start text-destructive" disabled={busy} onClick={() => {
              toast.dismiss("log-feedback");
              setAdding(false);
              setDeleteTarget(editing);
            }}>Delete activity</Button>}
          </DialogContent>
        </Dialog>
        <Dialog open={!!deleteTarget} onOpenChange={open => { if (!open) setDeleteTarget(null); }}>
          <DialogContent onCloseAutoFocus={event => {
            event.preventDefault();
            const trigger = activityEditorTrigger.current;
            if (trigger?.isConnected) trigger.focus();
            else addActivityTrigger.current?.focus();
          }}>
            <DialogHeader>
              <DialogTitle>Delete {deleteTarget?.name}?</DialogTitle>
              <DialogDescription>This permanently deletes the activity and its log history.</DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" onClick={() => setDeleteTarget(null)}>Cancel</Button>
              <Button variant="destructive" onClick={() => { if (deleteTarget) void deleteActivity(deleteTarget); }}>Delete activity</Button>
            </DialogFooter>
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
                Add one log for {logDay === dayKey() ? "today" : logDay}. You can undo it afterwards.
              </DialogDescription>
            </DialogHeader>
            {pastLog && <Field>
              <FieldLabel htmlFor="log-date">Choose date</FieldLabel>
              <Input id="log-date" type="date" max={dayKey()} value={logDay} onChange={e => setLogDay(e.target.value)} />
            </Field>}
            <DialogFooter>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => setConfirm(null)}
              >
                Cancel
              </Button>
              <Button disabled={busy || !logDay || logDay > dayKey()} onClick={() => void saveLog()}>
                {busy ? "Saving…" : "Yes, log it"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Dialog open={remindersOpen} onOpenChange={setRemindersOpen}>
          <DialogContent className="sm:max-w-sm">
            <DialogHeader><DialogTitle>Goal reminders</DialogTitle><DialogDescription>
              Get a reminder when there’s still time to meet a goal or when a goal was missed.
              {reminderMode === "open-tab"
                ? " Keep LogIt open. Checks run at 8 pm and 9 am in your browser’s time zone."
                : " Background delivery uses LogIt’s schedule. Goal dates follow your browser’s time zone."}
            </DialogDescription></DialogHeader>
            <DialogFooter className="grid grid-cols-2">
              <Button variant="outline" disabled={remindersBusy} onClick={() => {
                if (user && !remindersOn) localStorage.setItem("logit-reminders:" + user.uid, "off");
                setRemindersOpen(false);
              }}>{remindersOn ? "Close" : "Not now"}</Button>
              <Button disabled={!user || remindersBusy} onClick={async () => {
                if (!user) return;
                setRemindersBusy(true);
                try {
                  const mode = await setBrowserReminders(user, !remindersOn);
                  localStorage.setItem("logit-reminders:" + user.uid, mode);
                  setReminderMode(mode);
                  setRemindersOn(!remindersOn);
                  setRemindersOpen(false);
                  toast.success(mode === "open-tab" ? "Reminders are on while LogIt is open" : remindersOn ? "Reminders are off" : "Reminders are on");
                } catch (e) { error(e); } finally { setRemindersBusy(false); }
              }}>{remindersOn ? "Turn off" : "Enable reminders"}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        <Toaster theme={dark ? "dark" : "light"} />
        <p className="bottom-note">One day at a time.</p>
      </main>
    </TooltipProvider>
  );
}
