import type { NextConfig } from "next";

const allowedDevOrigins =
  process.env.OWL_NEXT_ALLOWED_DEV_ORIGINS?.split(",")
    .map((origin) => origin.trim())
    .filter(Boolean) ?? ["127.0.0.1", "*.local", "192.168.*.*", "10.*.*.*", "172.*.*.*"];

const nextConfig: NextConfig = {
  output: "standalone",
  allowedDevOrigins
};

export default nextConfig;
