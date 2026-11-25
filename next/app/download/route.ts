export function GET() {
  return new Response(null, {
    status: 307,
    headers: {
      Location: "https://github.com/wrestle-R/LogIt/releases/latest/download/logit.apk",
      "Cache-Control": "no-store",
    },
  });
}
