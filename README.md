# Android MCP Bridge 🌉

Android MCP Bridge is a personal, privacy-first bridge connecting conversational AI assistants (such as ChatGPT Custom Actions and Model Context Protocol / MCP clients) directly to your Android health data stored in [AndroidX Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect).

It enables private AI interactions—such as logging meals, reviewing nutrition history, tracking weight, or analyzing step counts—without exposing personal health records to centralized third-party servers or cloud databases.

> **Disclaimer**: This is a personal, unofficial utility designed for individual self-hosting. It is not affiliated with, endorsed by, or sponsored by Samsung, Google, or OpenAI.

---

## 🌟 Key Features

- **Read & Write Health Data**:
  - **Nutrition**: Read meals and macros (calories, protein, carbohydrates, fats, fiber, sodium); log new meals, save up to 20 at once, and correct a meal in place without duplicates.
  - **Weight**: Track weight records over time.
  - **Steps**: Read aggregated daily step counts from Health Connect.
  - **Water, sleep, workouts**: Read and log hydration, sleep sessions and exercise sessions.
  - **Heart rate & active calories**: Read summaries (average/min/max, resting average, kcal burned).
  - **Body measurements**: Read and log body fat % and lean body mass (Health Connect has no waist type).
  - **Daily summary & data freshness**: One-call day overview, and when each app (e.g. Samsung Health) last wrote each data type.
  - **Source identification**: Every record reports the app that wrote it, to avoid double counting.
- **Biometric & On-Device Approval**:
  - Record additions and read queries execute according to pre-approved client scopes.
  - Record deletions strictly require on-device biometric (fingerprint/face) or device PIN confirmation.
- **Dual AI Interface**:
  - **Model Context Protocol (MCP)**: Stateless Streamable HTTP MCP server (`@modelcontextprotocol/server`) providing 24 scoped tools (the extended ones are MCP-only; see [docs/development-log.md](docs/development-log.md)).
  - **ChatGPT Custom Actions**: REST API endpoints conforming to OpenAPI 3.0 specification (`tools/chatgpt-actions.yaml`).
- **OAuth 2.1 + PKCE Authorization**:
  - Secure authorization code flow with PKCE (`S256`) and interactive phone approval.
  - Tokens and grants are stored encrypted with automatic rotation and revocation support.
- **Privacy & Zero-Knowledge Architecture**:
  - No health bodies, record IDs, or raw tokens are stored in the Cloudflare Worker or logs.
  - Cloudflare Worker serves purely as an ephemeral WebSocket relay between ChatGPT and your Android phone.

---

## 🏗️ Architecture & How It Works

```
┌──────────────────────┐         HTTPS / MCP          ┌──────────────────────────────────┐
│                      │ ───────────────────────────> │                                  │
│   ChatGPT Client     │                              │   Cloudflare Worker              │
│ (Actions or MCP App) │ <─────────────────────────── │  (Relay + SQLite Durable Object) │
│                      │    OAuth 2.1 Bearer Token    │                                  │
└──────────────────────┘                              └──────────────────────────────────┘
                                                                       ▲
                                                                       │ Outbound WSS
                                                                       │ (Device Authenticated)
                                                                       ▼
                                                      ┌──────────────────────────────────┐
                                                      │                                  │
                                                      │   Android Phone                  │
                                                      │   (Android MCP Bridge App)            │
                                                      │                                  │
                                                      │   ┌──────────────────────────┐   │
                                                      │   │ Android Keystore (P-256) │   │
                                                      │   └──────────────────────────┘   │
                                                      │   ┌──────────────────────────┐   │
                                                      │   │ AndroidX Health Connect  │   │
                                                      │   └──────────────────────────┘   │
                                                      └──────────────────────────────────┘
```

1. **Outbound Relay**: The Android app establishes an outbound WebSocket connection (`wss://`) to your personal Cloudflare Worker. This eliminates the need for open firewall ports, static IP addresses, or home port-forwarding.
2. **Device Authentication**: The connection is authenticated using an asymmetric P-256 EC key pair generated in the hardware-backed **Android Keystore**. The backend only stores the public key and challenges each session with a cryptographic proof (nonce replay protection).
3. **Relay & Execution**:
   - ChatGPT sends an Action or MCP tool call to the Cloudflare Worker.
   - The Worker validates the OAuth 2.1 token / client scopes and forwards the request over WebSocket to the phone.
   - The phone independently verifies the request, checks device lock and battery status, queries AndroidX Health Connect, and sends the response back through the relay.

---

## 🔒 Security Model

- **Hardware-Backed Keystore**: Device identity keys never leave the phone's hardware security module (Secure Element / StrongBox).
- **Single-Use Pairing Secret**: Initial pairing requires a one-time bootstrap secret generated by the owner, which is invalidated immediately upon pairing.
- **Fail-Closed Deletion**: Destructive operations cannot be executed autonomously by the AI; they are held in a pending state until confirmed via biometrics on the phone screen.
- **Token Fingerprints**: The cloud relay only holds SHA-256 fingerprints of client tokens; raw tokens exist only on the client and phone.
- **Ephemeral Processing**: Request payloads pass through Worker memory without database persistence.

---

## 📁 Repository Structure

```
.
├── app/                  # Android application (Kotlin, Jetpack Compose, Health Connect, Ktor)
│   ├── src/main/java/    # UI, Health Connect repository, Keystore crypto, WebSocket relay
│   └── build.gradle.kts  # Android build configuration
├── backend/              # Cloudflare Worker relay service (TypeScript)
│   ├── src/              # Worker entry, Durable Object, MCP server, OAuth 2.1 provider
│   ├── test/             # Automated integration tests using Miniflare / Vitest
│   ├── wrangler.jsonc    # Cloudflare Wrangler deployment configuration
│   └── package.json      # Node.js dependencies and scripts
├── contracts/            # Protocol specifications
│   └── relay-protocol.md # WebSocket relay protocol specification and envelopes
├── docs/                 # Detailed documentation
│   ├── standalone-setup.md       # Step-by-step deployment and pairing guide
│   └── standalone-backend-plan.md# In-depth architectural design decisions
└── tools/                # Integration tools
    ├── chatgpt-actions.yaml      # OpenAPI 3.0 schema for ChatGPT Custom Actions
    └── connect-chatgpt.sh        # Local USB-tethered debugging script
```

---

## 🚀 Getting Started

### Prerequisites

- **Android Device**: Running Android 14+ (or Android 9+ with the Health Connect app installed), with AndroidX Health Connect configured.
- **Node.js**: v20+ and npm.
- **Java / Android Studio**: JDK 17 or 21, Android SDK 35/36.
- **Cloudflare Account**: Free tier is sufficient (Workers + KV).

---

### Step 1: Deploy Backend (Cloudflare Worker)

1. Navigate to the backend directory and install dependencies:
   ```bash
   cd backend
   npm ci
   ```

2. Login to Cloudflare via Wrangler:
   ```bash
   npx wrangler login
   ```

3. Create the OAuth KV namespace:
   ```bash
   npx wrangler kv namespace create OAUTH_KV
   ```
   Add the generated `id` to `backend/wrangler.jsonc` under `kv_namespaces`.

4. Configure your environment secrets:
   ```bash
   # Set the single-use pairing token for the Android app
   npx wrangler secret put OWNER_BOOTSTRAP_TOKEN

   # Set OAuth client credentials for ChatGPT
   npx wrangler secret put OAUTH_CLIENT_ID
   npx wrangler secret put OAUTH_CLIENT_SECRET
   npx wrangler secret put OAUTH_REDIRECT_URI
   ```
   *(For ChatGPT, use `https://chatgpt.com/connector_platform_oauth_redirect` as the redirect URI).*

5. Create `backend/.env` with your Worker URL:
   ```bash
   cp .env.example .env
   # Edit .env and set PUBLIC_ORIGIN=https://<your-worker-subdomain>.workers.dev
   ```

6. Deploy the Worker:
   ```bash
   npm run deploy
   ```

---

### Step 2: Build & Install Android App

1. Build the debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```

2. Install on your connected device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Open the app:
   - Grant Health Connect permissions (Nutrition, Weight, Steps).
   - Enter your Cloudflare Worker URL (`https://...workers.dev`) and the `OWNER_BOOTSTRAP_TOKEN`.
   - Tap **Pair Device**.
   - Tap **Start health access** to start the background relay service.

---

### Step 3: Connect to ChatGPT

#### Option A: Model Context Protocol (MCP) Plugin
1. Go to **ChatGPT → Settings → Connected Apps / Plugins**.
2. Select **Add custom MCP server**.
3. Enter your Worker endpoint:
   ```text
   https://<your-worker-subdomain>.workers.dev/mcp
   ```
4. Authenticate using OAuth with your `OAUTH_CLIENT_ID` and `OAUTH_CLIENT_SECRET`.
5. Approve the connection prompt on your Android phone.

#### Option B: Custom GPT Action (OpenAPI)
1. In ChatGPT, create a new Custom GPT.
2. Under **Actions**, select **Create new action**.
3. Import `tools/chatgpt-actions.yaml`, updating `servers.url` to match your Cloudflare Worker URL.
4. Set Authentication to **OAuth** and provide the client ID, secret, and authorization/token endpoints.

---

## 🧪 Testing

### Backend Unit & Integration Tests

The backend includes test suites verifying pairing, cryptographic signature validation, OAuth PKCE, and MCP tool handling using Vitest and `@cloudflare/vitest-plugin`:

```bash
cd backend
npm run typecheck
npm test
```

### Android Unit Tests

Run local JVM unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

---

## 📄 License

MIT License. See [LICENSE](LICENSE) for details.
