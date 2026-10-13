import type { Env as BridgeEnv } from "../src/bridge";

declare global {
  namespace Cloudflare {
    interface Env extends BridgeEnv {}
  }
}
