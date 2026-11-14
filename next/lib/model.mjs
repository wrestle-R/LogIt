export const dayKey = (date = new Date()) =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
export function count(habit, day) {
  return Math.max(
    Number(habit.dailyCounts?.[day] || 0),
    habit.completions?.includes(day) ? 1 : 0,
  );
}
export function total(habit) {
  return [
    ...new Set([
      ...(habit.completions || []),
      ...Object.keys(habit.dailyCounts || {}),
    ]),
  ].reduce((sum, day) => sum + count(habit, day), 0);
}
export function change(habit, day, delta) {
  const next = Math.max(0, count(habit, day) + delta);
  return {
    ...habit,
    dailyCounts: { ...habit.dailyCounts, [day]: next },
    completions: next
      ? [...new Set([...(habit.completions || []), day])]
      : (habit.completions || []).filter((d) => d !== day),
  };
}
export function streak(habit, today = dayKey()) {
  let n = 0;
  const cursor = new Date(`${today}T00:00:00Z`);
  while (count(habit, cursor.toISOString().slice(0, 10)) > 0) {
    n++;
    cursor.setUTCDate(cursor.getUTCDate() - 1);
  }
  return n;
}
