import { dayKey, reminderProgress, shiftDay } from "./model.mjs";
import type { User } from "firebase/auth";

export async function setBrowserReminders(user: User, enabled: boolean) {
  if (!("serviceWorker" in navigator) || !("Notification" in window) || !("PushManager" in window))
    throw new Error("This browser doesn't support goal reminders.");
  if (enabled && await Notification.requestPermission() !== "granted")
    throw new Error("Notifications are off. You can change that in your browser settings.");
  const registration = await navigator.serviceWorker.register("/sw.js");
  const active = registration.active ? registration : await navigator.serviceWorker.ready;
  let subscription = await active.pushManager.getSubscription();
  if (enabled && !subscription) {
    const { key } = await fetch("/api/reminders", { cache: "no-store" }).then(response => response.json());
    if (!key) return "open-tab";
    subscription = await active.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  }
  if (!subscription) return "off";
  const response = await fetch("/api/reminders", {
    method: enabled ? "POST" : "DELETE",
    headers: { Authorization: "Bearer " + await user.getIdToken(), "Content-Type": "application/json" },
    body: JSON.stringify({ ...subscription.toJSON(), timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone }),
  });
  if (!response.ok && enabled) throw new Error("Couldn't enable reminders. Give it another try.");
  if (!enabled && !await subscription.unsubscribe() && !response.ok)
    throw new Error("Couldn't turn off reminders. Give it another try.");
  return enabled ? "push" : "off";
}

export function watchPushReminderTimeZone(user: User) {
  if (!("serviceWorker" in navigator)) return;
  const request = new AbortController();
  let lastSaved: string | undefined;
  let inFlight = false;
  async function refresh() {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!navigator.onLine || timeZone === lastSaved || inFlight) return;
    inFlight = true;
    try {
      const registration = await navigator.serviceWorker.getRegistration("/sw.js");
      const subscription = await registration?.pushManager?.getSubscription();
      if (!subscription || request.signal.aborted) return;
      const response = await fetch("/api/reminders", {
        method: "PATCH",
        headers: { Authorization: "Bearer " + await user.getIdToken(), "Content-Type": "application/json" },
        body: JSON.stringify({ ...subscription.toJSON(), timeZone }),
        signal: request.signal,
      });
      if (response.ok) lastSaved = timeZone;
    } catch {
      // A network failure leaves the last saved zone intact. Retry on the next check.
    } finally {
      inFlight = false;
    }
  }
  void refresh();
  const timer = window.setInterval(refresh, 60_000);
  window.addEventListener("focus", refresh);
  window.addEventListener("online", refresh);
  return () => {
    request.abort();
    window.clearInterval(timer);
    window.removeEventListener("focus", refresh);
    window.removeEventListener("online", refresh);
  };
}

export function checkOpenTabReminders(
  uid: string,
  habits: { name: string }[],
  now = new Date(),
) {
  if (!("Notification" in window) || Notification.permission !== "granted" || !("serviceWorker" in navigator)) return;
  const hour = now.getHours();
  const missed = hour >= 9 && hour < 12;
  if (!missed && hour < 20) return;
  const day = shiftDay(dayKey(now), missed ? -1 : 0);
  const ledger = `logit-open-reminder:${uid}:${missed}`;
  const delivered = localStorage.getItem(ledger);
  if (delivered && delivered >= day) return;
  const names = habits.filter(habit => reminderProgress(habit, day)).map(habit => habit.name).slice(0, 3);
  if (!names.length) return;
  localStorage.setItem(ledger, day);
  void navigator.serviceWorker.ready.then(registration => registration.showNotification(
    missed ? "A goal slipped by" : "Still time for a log",
    {
      body: missed ? `${names.join(", ")} came up short. If you forgot to log it, you can still add it.`
        : `${names.join(", ")} still ${names.length === 1 ? "has" : "have"} a little room to go.`,
      tag: `goal-${day}-${missed}`,
      icon: "/logo.svg",
    },
  )).catch(() => {
    if (localStorage.getItem(ledger) === day) {
      if (delivered) localStorage.setItem(ledger, delivered);
      else localStorage.removeItem(ledger);
    }
  });
}
