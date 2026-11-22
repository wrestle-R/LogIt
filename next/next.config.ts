import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  async headers() {
    return [{
      source: "/downloads/:file*",
      headers: [
        { key: "Content-Type", value: "application/vnd.android.package-archive" },
        { key: "Content-Disposition", value: 'attachment; filename="logit.apk"' },
        { key: "Cache-Control", value: "public, max-age=31536000, immutable" },
      ],
    }];
  },
};

export default nextConfig;
