import { describe, expect, it } from "vitest";
import { thumbprint, verifyConnectProof, verifyPairingProof, type PublicDeviceKey } from "../src/auth";
import fixture from "./kotlin-proof.fixture.json";

// Proofs produced by the Android DeviceJws code (JDK SHA256withECDSA + DER-to-JOSE conversion) with a synthetic key.
describe("Android proof interoperability", () => {
  const key = fixture.jwk as PublicDeviceKey;
  it("verifies Kotlin pairing and connect proofs with the backend verifier", async () => {
    expect(await thumbprint(key)).toBe(fixture.kid);
    const paired = await verifyPairingProof(fixture.pair, "https://bridge.example.test", 1_000);
    expect(paired && await thumbprint(paired.key)).toBe(fixture.kid);
    const connect = await verifyConnectProof(fixture.connect, key, fixture.kid, "https://bridge.example.test/device/connect", 1_000);
    expect(connect?.pairing_generation).toBe(3);
    expect(await verifyConnectProof(fixture.connect, key, fixture.kid, "https://other.example.test/device/connect", 1_000)).toBeNull();
    expect(await verifyConnectProof(fixture.connect, key, fixture.kid, "https://bridge.example.test/device/connect", 2_000)).toBeNull();
  });
});
