import { parseSubscription, reminderAdmin, remindersConfigured, subscriptionId } from "@/lib/web-reminders";

export function GET() {
  return Response.json({ key: remindersConfigured() ? process.env.NEXT_PUBLIC_LOGIT_VAPID_KEY : null });
}

async function change(request: Request, enabled: boolean) {
  if (!remindersConfigured()) return new Response(null, { status: 503 });
  const { auth, db } = reminderAdmin();
  const token = request.headers.get("authorization")?.match(/^Bearer (\S+)$/)?.[1];
  const user = token ? await auth.verifyIdToken(token).catch(() => null) : null;
  if (!user) return new Response(null, { status: 401 });
  const raw = await request.text();
  if (raw.length > 4096) return new Response(null, { status: 413 });
  const subscription = parseSubscription(await Promise.resolve().then(() => JSON.parse(raw)).catch(() => null));
  if (!subscription) return new Response(null, { status: 400 });
  const collection = db.collection("logitWebReminders");
  const ref = collection.doc(subscriptionId(user.uid, subscription.endpoint));
  if (enabled) {
    // Each browser endpoint belongs to the account that most recently enabled it.
    const previous = await collection.where("subscription.endpoint", "==", subscription.endpoint).get();
    const batch = db.batch();
    previous.docs.filter(doc => doc.id !== ref.id).forEach(doc => batch.delete(doc.ref));
    batch.set(ref, { uid: user.uid, subscription }, { merge: true });
    await batch.commit();
  } else await ref.delete();
  return new Response(null, { status: 204 });
}

export function POST(request: Request) { return change(request, true); }
export function DELETE(request: Request) { return change(request, false); }
