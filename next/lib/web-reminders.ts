import { cert, getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { createHash } from "node:crypto";
import type { PushSubscription } from "web-push";

export function remindersConfigured() {
  return Boolean(process.env.FIREBASE_ADMIN_CREDENTIALS && process.env.NEXT_PUBLIC_LOGIT_VAPID_KEY &&
    process.env.LOGIT_VAPID_PRIVATE_KEY && process.env.LOGIT_VAPID_SUBJECT && process.env.CRON_SECRET);
}

export function reminderAdmin() {
  const app = getApps().find(app => app.name === "logit-reminders") || initializeApp({
    credential: cert(JSON.parse(process.env.FIREBASE_ADMIN_CREDENTIALS!)),
  }, "logit-reminders");
  return { auth: getAuth(app), db: getFirestore(app) };
}

export function subscriptionId(uid: string, endpoint: string) {
  return createHash("sha256").update(JSON.stringify([uid, endpoint])).digest("hex");
}

export function parseSubscription(value: unknown): PushSubscription | null {
  if (!value || typeof value !== "object") return null;
  const { endpoint, keys } = value as PushSubscription;
  if (typeof endpoint !== "string" || endpoint.length > 2048 ||
    !keys || typeof keys.auth !== "string" || typeof keys.p256dh !== "string" ||
    !/^[\w-]{22}$/.test(keys.auth) || !/^[\w-]{87}$/.test(keys.p256dh)) return null;
  try {
    const url = new URL(endpoint);
    const host = url.hostname;
    const allowed = ["fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com", "notify.windows.com"];
    if (url.protocol !== "https:" || url.port || url.username || url.password || url.hash ||
      !allowed.some(domain => host === domain || domain === "notify.windows.com" && host.endsWith("." + domain))) return null;
    return { endpoint, keys: { auth: keys.auth, p256dh: keys.p256dh } };
  } catch { return null; }
}
