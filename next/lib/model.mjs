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

export function shiftDay(day, offset) {
  const date = new Date(day + "T00:00:00Z");
  date.setUTCDate(date.getUTCDate() + offset);
  return date.toISOString().slice(0, 10);
}

export function monday(day) {
  return shiftDay(day, -((new Date(day + "T00:00:00Z").getUTCDay() + 6) % 7));
}

export function goalProgress(habit, day) {
  const goal = habit.goal;
  if (!goal || !["daily", "weekly"].includes(goal.period) || !Number.isInteger(goal.target) || goal.target < 1 || goal.target > 999) return null;
  const start = goal.period === "weekly" ? monday(day) : day;
  const days = goal.period === "weekly" ? 7 : 1;
  let logged = 0;
  for (let i = 0; i < days; i++) {
    const date = shiftDay(start, i);
    if (date <= day) logged += count(habit, date);
  }
  return { start, logged, target: goal.target, period: goal.period };
}

export function activityStats(habit, today) {
  const first = shiftDay(today, -27);
  const days = [...new Set([...(habit.completions || []), ...Object.keys(habit.dailyCounts || {})])]
    .filter(day => day <= today && count(habit, day) > 0).sort();
  const recent = days.filter(day => day >= first);
  return {
    first: days[0],
    activeDays: days.length,
    recentDays: recent.length,
    recentLogs: recent.reduce((sum, day) => sum + count(habit, day), 0),
    weeks: Array.from({length: 4}, (_, i) => {
      const start = shiftDay(today, -27 + i * 7);
      const end = shiftDay(start, 6);
      return recent.filter(day => day >= start && day <= end).reduce((sum, day) => sum + count(habit, day), 0);
    }),
  };
}
