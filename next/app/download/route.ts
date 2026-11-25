export function GET(request: Request) {
  return new Response(null, {
    status: 307,
    headers: {
      Location: new URL("/downloads/logit-0.3.8.apk", request.url).href,
      "Cache-Control": "no-store",
    },
  });
}
