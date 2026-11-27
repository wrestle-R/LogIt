import { parseSubscription, reminderAdmin, remindersConfigured, subscriptionId } from "@/lib/web-reminders";

export function GET() {
  return Response.json({ key: remindersConfigured() ? process.env.NEXT_PUBLIC_LOGIT_VAPID_KEY : null });
}

async function change(request: Request, action: "enable" | "disable" | "refresh") {
  if (!remindersConfigured()) return new Response(null, { status: 503 });
  const { auth, db } = reminderAdmin();
  const token = request.headers.get("authorization")?.match(/^Bearer (\S+)$/)?.[1];
  const user = token ? await auth.verifyIdToken(token).catch(() => null) : null;
  if (!user) return new Response(null, { status: 401 });
  const raw = await request.text();
  if (raw.length > 4096) return new Response(null, { status: 413 });
  const data = await Promise.resolve().then(() => JSON.parse(raw)).catch(() => null);
  const subscription = parseSubscription(data);
  if (!subscription) return new Response(null, { status: 400 });
  const collection = db.collection("logitWebReminders");
  const ref = collection.doc(subscriptionId(user.uid, subscription.endpoint));
  if (action === "disable") {
    await ref.delete();
  } else {
    let timeZone: string | undefined;
    if (typeof data?.timeZone === "string" && data.timeZone.length <= 64) {
      try {
        timeZone = new Intl.DateTimeFormat("en", { timeZone: data.timeZone }).resolvedOptions().timeZone;
      } catch { return new Response(null, { status: 400 }); }
    }
    if (action === "refresh") {
      if (!timeZone) return new Response(null, { status: 400 });
      await db.runTransaction(async transaction => {
        const current = await transaction.get(ref);
        if (current.exists && current.get("timeZone") !== timeZone) transaction.update(ref, { timeZone });
      });
      return new Response(null, { status: 204 });
    }
    // Each browser endpoint belongs to the account that most recently enabled it.
    const previous = await collection.where("subscription.endpoint", "==", subscription.endpoint).get();
    const batch = db.batch();
    previous.docs.filter(doc => doc.id !== ref.id).forEach(doc => batch.delete(doc.ref));
    batch.set(ref, { uid: user.uid, subscription, ...(timeZone ? { timeZone } : {}) }, { merge: true });
    await batch.commit();
  }
  return new Response(null, { status: 204 });
}

export function POST(request: Request) { return change(request, "enable"); }
export function PATCH(request: Request) { return change(request, "refresh"); }
export function DELETE(request: Request) { return change(request, "disable"); }
