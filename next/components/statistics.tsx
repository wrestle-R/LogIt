"use client";

import { useState } from "react";
import { ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import { count, dayKey, goalProgress, shiftDay, total } from "@/lib/model.mjs";

type Activity = { id: string; name: string; color?: number; completions: string[]; dailyCounts?: Record<string, number> };
const formatDate = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
const dateLabel = (day: string) => formatDate.format(new Date(day + "T00:00:00Z"));

export function Statistics({ habits, palette, onBack }: { habits: Activity[]; palette: string[]; onBack: () => void }) {
  const [range, setRange] = useState(28);
  const [activity, setActivity] = useState("");
  const selected = activity ? habits.filter(item => item.id === activity) : habits;
  const today = dayKey();
  const start = shiftDay(today, 1 - range);
  const counts = Array.from({ length: range }, (_, i) =>
    selected.reduce((sum, item) => sum + count(item, shiftDay(start, i)), 0),
  );
  const step = range === 7 ? 1 : 7;
  const bars = Array.from({ length: range / step }, (_, i) =>
    counts.slice(i * step, (i + 1) * step).reduce((sum, value) => sum + value, 0),
  );
  const logs = counts.reduce((sum, value) => sum + value, 0);
  const activeDays = counts.filter(Boolean).length;
  const best = Math.max(0, ...counts);
  const color = selected.length === 1 ? palette[selected[0].color || 0] : palette[0];
  return (
    <main className="shell stats-page">
      <Button size="sm" variant="ghost" onClick={onBack}><ArrowLeft /> Activities</Button>
      <header className="stats-heading"><h1>Your statistics</h1><p>See how often you showed up.</p></header>
      <div className="stats-filters">
        <div className="stats-segments" role="group" aria-label="Time range">
          {[7, 28, 84].map(days => <Button key={days} size="sm" variant={range === days ? "secondary" : "ghost"}
            aria-pressed={range === days} onClick={() => setRange(days)}>{days} days</Button>)}
        </div>
        <select aria-label="Activity" className="logit-select" value={activity} onChange={event => setActivity(event.target.value)}>
          <option value="">All activities</option>
          {habits.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      </div>
      <div className="stats-metrics">
        <div><strong>{logs}</strong><span>Logs</span></div>
        <div><strong>{activeDays}<small> / {range}</small></strong><span>Active days</span></div>
        <div><strong>{best}</strong><span>Most logs in a day</span></div>
      </div>
      <section className="stats-chart">
        <h2>Activity over time</h2>
        <p>{step === 1 ? "Logs by day" : "Logs by week"}</p>
        {logs ? <>
          <div className="stats-columns" role="img" aria-label={bars.map((value, i) => `${dateLabel(shiftDay(start, i * step))}: ${value} logs`).join(", ")}
            style={{ gridTemplateColumns: `repeat(${bars.length}, minmax(0, 1fr))` }}>
            {bars.map((value, i) => <div key={i} title={`${dateLabel(shiftDay(start, i * step))}: ${value} logs`}>
              <span>{value}</span><i style={{ height: Math.max(2, value * 112 / Math.max(...bars)), background: value ? color : "var(--border)" }} />
            </div>)}
          </div>
          <p className="logit-chart-range"><span>{dateLabel(start)}</span><span>{dateLabel(today)}</span></p>
        </> : <p className="stats-empty">Your next log will show up here.</p>}
      </section>
      <section className="stats-breakdown">
        <h2>By activity</h2>
        {selected.map(item => {
          const inRange = counts.length && Array.from({ length: range }, (_, i) => count(item, shiftDay(start, i))).reduce((a, b) => a + b, 0);
          const goal = goalProgress(item, today);
          return <div key={item.id} className="stats-activity">
            <i style={{background: palette[item.color || 0]}} aria-hidden />
            <div><h3>{item.name}</h3><p>{total(item)} all time{goal && ` · ${goal.logged} of ${goal.target} ${goal.period === "weekly" ? "this week" : "today"}`}</p></div>
            <strong>{inRange}<small> logs</small></strong>
          </div>;
        })}
        {!selected.length && <p>Add an activity to start tracking it.</p>}
      </section>
    </main>
  );
}
