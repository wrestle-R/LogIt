"use client";

import Image from "next/image";
import { Download, ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export function AndroidDownload() {
  return (
    <Dialog>
      <Tooltip>
        <TooltipTrigger asChild>
          <DialogTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Download Android app"
            >
              <Download />
            </Button>
          </DialogTrigger>
        </TooltipTrigger>
        <TooltipContent>Download Android app</TooltipContent>
      </Tooltip>
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-md">
        <Image src="/logo.svg" alt="" width={40} height={40} />
        <DialogHeader className="text-left">
          <DialogTitle>LogIt for Android</DialogTitle>
          <DialogDescription>
            Log activities from your home screen.
          </DialogDescription>
        </DialogHeader>
        <Badge variant="secondary">v0.3.2 · APK · Android 8.0+</Badge>
        <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-muted-foreground">
          <li>Download the APK and open it on your Android phone.</li>
          <li>If prompted, allow your browser to install the app.</li>
          <li>Sign in with the Google account you use on the website.</li>
        </ol>
        <DialogFooter>
          <Button asChild variant="outline">
            <a
              href="https://github.com/wrestle-R/LogIt/releases/latest"
              target="_blank"
              rel="noopener noreferrer"
            >
              View release <ExternalLink data-icon="inline-end" />
            </a>
          </Button>
          <Button asChild>
            <a href="/download">
              <Download data-icon="inline-start" />
              Download APK
            </a>
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
