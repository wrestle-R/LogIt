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
    body: JSON.stringify(subscription),
  });
  if (!response.ok && enabled) throw new Error("Couldn't enable reminders. Give it another try.");
  if (!enabled && !await subscription.unsubscribe() && !response.ok)
    throw new Error("Couldn't turn off reminders. Give it another try.");
  return enabled ? "push" : "off";
}

export function checkOpenTabReminders(
  uid: string,
  habits: { name: string }[],
  now = new Date(),
) {
  if (!("Notification" in window) || Notification.permission !== "granted" || !("serviceWorker" in navigator)) return;
  const hour = Number(new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Kolkata", hour: "numeric", hourCycle: "h23",
  }).format(now));
  const missed = hour >= 9 && hour < 12;
  if (!missed && hour < 20) return;
  const day = shiftDay(dayKey(now), missed ? -1 : 0);
  const ledger = `logit-open-reminder:${uid}:${missed}`;
  if (localStorage.getItem(ledger) === day) return;
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
    if (localStorage.getItem(ledger) === day) localStorage.removeItem(ledger);
  });
}
