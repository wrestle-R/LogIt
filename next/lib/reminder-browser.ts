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
    if (!key) throw new Error("Web reminders aren't available yet.");
    subscription = await active.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  }
  if (!subscription) return;
  const response = await fetch("/api/reminders", {
    method: enabled ? "POST" : "DELETE",
    headers: { Authorization: "Bearer " + await user.getIdToken(), "Content-Type": "application/json" },
    body: JSON.stringify(subscription),
  });
  if (!response.ok && enabled) throw new Error("Couldn't enable reminders. Give it another try.");
  if (!enabled && !await subscription.unsubscribe() && !response.ok)
    throw new Error("Couldn't turn off reminders. Give it another try.");
}
