import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

// Synthetic test-only values. They are not deployable credentials.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          PUBLIC_ORIGIN: "https://bridge.example.test",
          OWNER_BOOTSTRAP_TOKEN: "test-bootstrap-0123456789abcdefghijklmnopqrstuvwxyz",
          RELAY_DEADLINE_MS: "600",
          OAUTH_CLIENT_ID: "test-chatgpt-client",
          OAUTH_CLIENT_SECRET: "test-client-secret-0123456789abcdefghijklmnop",
          OAUTH_REDIRECT_URI: "https://chatgpt.example.test/connector/oauth/callback-1",
        },
        kvNamespaces: ["OAUTH_KV"],
      },
    }),
  ],
});
