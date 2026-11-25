import { timingSafeEqual } from "node:crypto";
import type { QuerySnapshot } from "firebase-admin/firestore";
import { sendNotification } from "web-push";
import { reminderAdmin, remindersConfigured } from "@/lib/web-reminders";
import { dayKey, reminderProgress, shiftDay } from "@/lib/model.mjs";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  if (!remindersConfigured()) return new Response(null, { status: 503 });
  const expected = Buffer.from("Bearer " + process.env.CRON_SECRET);
  const given = Buffer.from(request.headers.get("authorization") || "");
  if (given.length !== expected.length || !timingSafeEqual(expected, given)) return new Response(null, { status: 401 });

  const now = new Date();
  const hour = Number(new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Kolkata", hour: "numeric", hourCycle: "h23" }).format(now));
  const missed = hour >= 9 && hour < 12;
  if (!missed && hour < 20) return Response.json({ delivered: 0 });
  const day = shiftDay(dayKey(now), missed ? -1 : 0);
  const slot = missed ? "lastMissed" : "lastEvening";
  const { db } = reminderAdmin();
  const subscriptions = await db.collection("logitWebReminders").get();
  const users = new Map<string, Promise<QuerySnapshot>>();
  let delivered = 0;
  let failed = 0;
  for (const doc of subscriptions.docs) {
    const { uid, subscription } = doc.data();
    if (doc.get(slot) === day) continue;
    try {
      const habits = users.get(uid) || db.collection("habits").where("userId", "==", uid).get();
      users.set(uid, habits);
      const incomplete = (await habits).docs.flatMap(h => {
        const habit = h.data();
        const progress = reminderProgress(habit, day);
        if (!progress) return [];
        return [String(habit.name || "Activity").slice(0, 80)];
      });
      if (!incomplete.length) continue;
      const claimed = await db.runTransaction(async transaction => {
        const fresh = await transaction.get(doc.ref);
        if (!fresh.exists || fresh.get(slot) === day) return false;
        transaction.update(doc.ref, { [slot]: day });
        return true;
      });
      if (!claimed) continue;
      try {
        await sendNotification(subscription, JSON.stringify({
          title: missed ? "A goal slipped by" : "Still time for a log",
          body: missed ? `${incomplete.slice(0, 3).join(", ")} came up short. If you forgot to log it, you can still add it.`
            : `${incomplete.slice(0, 3).join(", ")} still ${incomplete.length === 1 ? "has" : "have"} a little room to go.`,
          tag: `goal-${day}-${slot}`,
        }), { TTL: 3600, timeout: 10000, vapidDetails: {
          subject: process.env.LOGIT_VAPID_SUBJECT!,
          publicKey: process.env.NEXT_PUBLIC_LOGIT_VAPID_KEY!,
          privateKey: process.env.LOGIT_VAPID_PRIVATE_KEY!,
        } });
        delivered++;
      } catch (error) {
        const status = (error as { statusCode?: number }).statusCode;
        if (status === 404 || status === 410) await doc.ref.delete();
        else {
          await db.runTransaction(async transaction => {
            const current = await transaction.get(doc.ref);
            if (current.get(slot) === day) transaction.update(doc.ref, { [slot]: null });
          });
          failed++;
        }
      }
    } catch { failed++; }
  }
  return Response.json({ delivered, failed });
}
