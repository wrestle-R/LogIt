"use client";

import { useLayoutEffect, useState } from "react";
import { Dialog as Tour } from "radix-ui";
import { Button } from "@/components/ui/button";

const steps = [
  { target: "add", title: "Make your first activity", copy: "Add an activity, give it a name, and start filling in its history." },
  { target: "goal", title: "Set a goal", copy: "Choose a daily or weekly target when you add or edit an activity." },
  { target: "log-history", title: "Log from a graph", copy: "Double-click, double-tap, or hold a graph to choose a log date. Undo takes back a log. Redo restores it on the original date." },
  { target: "reminders", title: "Get goal reminders", copy: "Open Goal reminders to get a nudge before a goal is due or a follow-up if you miss one." },
  { target: "android", title: "Widgets and phone tracking", copy: "Use LogIt on Android to add home screen widgets or set up automations for app use, screen time goals, and Health Connect. You can also track sleep." },
];

export function Introduction({ onDone }: { onDone: () => void }) {
  const [index, setIndex] = useState(0);
  const [highlight, setHighlight] = useState<DOMRect>();
  const step = steps[index];

  useLayoutEffect(() => {
    const target = document.querySelector(`[data-intro="${step.target}"]`) ??
      document.querySelector('[data-intro="add"]');
    target?.scrollIntoView({ block: "center", behavior: "instant" });
    const update = () => setHighlight(target?.getBoundingClientRect());
    update();
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, { passive: true });
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update);
    };
  }, [step]);

  function previous() {
    setIndex((current) => Math.max(0, current - 1));
  }

  function next() {
    if (index === steps.length - 1) onDone();
    else setIndex(index + 1);
  }

  return (
    <Tour.Root open onOpenChange={(open) => { if (!open) onDone(); }}>
      <Tour.Portal>
        <Tour.Overlay
          className="fixed inset-0 z-50 overflow-hidden"
          onPointerDown={(event) => {
            event.preventDefault();
            if (event.clientX >= window.innerWidth / 2) next();
            else previous();
          }}
        >
          {highlight && highlight.width > 0 ? (
            <svg aria-hidden className="pointer-events-none absolute inset-0 size-full overflow-hidden">
              <defs>
                <mask id="introduction-spotlight">
                  <rect width="100%" height="100%" fill="white" />
                  <rect x={highlight.left - 4} y={highlight.top - 4}
                    width={highlight.width + 8} height={highlight.height + 8} rx="8" fill="black" />
                </mask>
              </defs>
              <rect width="100%" height="100%" fill="black" opacity=".62" mask="url(#introduction-spotlight)" />
              <rect x={highlight.left - 4} y={highlight.top - 4}
                width={highlight.width + 8} height={highlight.height + 8} rx="8"
                fill="none" stroke="#4ade80" strokeWidth="2" />
            </svg>
          ) : <div aria-hidden className="pointer-events-none absolute inset-0 bg-black/70" />}
        </Tour.Overlay>
        <Tour.Content
          className="fixed bottom-4 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-md -translate-x-1/2 overflow-y-auto rounded-xl border bg-background p-4 text-foreground shadow-xl"
          onPointerDownOutside={(event) => event.preventDefault()}
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            document.getElementById("introduction-title")?.focus();
          }}
        >
          <div className="mb-1 flex items-center justify-between">
            <span className="text-sm text-muted-foreground">Introduction · {index + 1} of {steps.length}</span>
            <Button variant="ghost" onClick={onDone}>Skip</Button>
          </div>
          <Tour.Title id="introduction-title" tabIndex={-1} className="text-xl font-semibold outline-none" aria-live="polite">
            {step.title}
          </Tour.Title>
          <Tour.Description className="mt-1 text-sm text-muted-foreground">{step.copy}</Tour.Description>
          <div className="mt-4 flex justify-between">
            <Button variant="ghost" disabled={index === 0} onClick={previous}>Previous</Button>
            <Button onClick={next}>{index === steps.length - 1 ? "Done" : "Next"}</Button>
          </div>
        </Tour.Content>
      </Tour.Portal>
    </Tour.Root>
  );
}
