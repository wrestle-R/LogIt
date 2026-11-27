import { timingSafeEqual } from "node:crypto";
import type { QuerySnapshot } from "firebase-admin/firestore";
import { sendNotification, type PushSubscription } from "web-push";
import { reminderAdmin, remindersConfigured } from "@/lib/web-reminders";
import { reminderProgress } from "@/lib/model.mjs";
import { dueReminder } from "@/lib/reminder-schedule";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  if (!remindersConfigured()) return new Response(null, { status: 503 });
  const expected = Buffer.from("Bearer " + process.env.CRON_SECRET);
  const given = Buffer.from(request.headers.get("authorization") || "");
  if (given.length !== expected.length || !timingSafeEqual(expected, given)) return new Response(null, { status: 401 });

  const now = new Date();
  const { db } = reminderAdmin();
  const subscriptions = await db.collection("logitWebReminders").get();
  const users = new Map<string, Promise<QuerySnapshot>>();
  let delivered = 0;
  let failed = 0;
  for (const doc of subscriptions.docs) {
    try {
      const { uid, timeZone } = doc.data();
      const due = dueReminder(now, timeZone);
      if (!due) continue;
      const { day, slot } = due;
      const missed = slot === "lastMissed";
      if ((doc.get(slot) ?? "") >= day) continue;
      const habits = users.get(uid) || db.collection("habits").where("userId", "==", uid).get();
      users.set(uid, habits);
      const incomplete = (await habits).docs.flatMap(h => {
        const habit = h.data();
        const progress = reminderProgress(habit, day);
        if (!progress) return [];
        return [String(habit.name || "Activity").slice(0, 80)];
      });
      if (!incomplete.length) continue;
      const claim = await db.runTransaction(async transaction => {
        const fresh = await transaction.get(doc.ref);
        const previousDay = fresh.get(slot) as string | null | undefined;
        if (!fresh.createTime?.isEqual(doc.createTime) || fresh.get("timeZone") !== timeZone ||
          (previousDay ?? "") >= day) return null;
        const subscription = fresh.get("subscription") as PushSubscription;
        transaction.update(doc.ref, { [slot]: day });
        return { subscription, previousDay };
      });
      if (!claim) continue;
      try {
        await sendNotification(claim.subscription, JSON.stringify({
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
        if (status === 404 || status === 410 || status === 429) {
          await db.runTransaction(async transaction => {
            const fresh = await transaction.get(doc.ref);
            if (!fresh.createTime?.isEqual(doc.createTime) || fresh.get(slot) !== day) return;
            const subscription = fresh.get("subscription") as PushSubscription;
            if (status === 429) {
              transaction.update(doc.ref, { [slot]: claim.previousDay ?? null });
            } else if (subscription.endpoint === claim.subscription.endpoint &&
              subscription.keys?.auth === claim.subscription.keys?.auth &&
              subscription.keys?.p256dh === claim.subscription.keys?.p256dh) {
              transaction.delete(doc.ref);
            }
          });
        }
        // An ambiguous failure may have reached the browser. Keep its claim to avoid a second send.
        if (status !== 404 && status !== 410) failed++;
      }
    } catch { failed++; }
  }
  return Response.json({ delivered, failed });
}
