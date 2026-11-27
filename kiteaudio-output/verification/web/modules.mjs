// Loads the exports the verification page needs from the linked web test modules.
// Each takes a platform, "js" or "wasmJs", and the repository root as a URL.

const ARTIFACTS = "build/artifacts/CompiledWebArtifact";

// kotlin-test registers Kotlin/JS tests through describe and it when they exist. Defining them as
// no-ops means importing a test file runs no tests.
globalThis.describe ??= () => {};
globalThis.it ??= () => {};

function js(root, module, file) {
  const dir = module.replace("kiteaudio-", "kiteaudio_");
  return new URL(`${ARTIFACTS}/${module}jsTestrelease/kotlin-output/kotlin_${dir}_test/io/github/yuroyami/kiteaudio/${file}.export.mjs`, root);
}

function wasm(root, module) {
  return new URL(`${ARTIFACTS}/${module}wasmJsTestrelease/kotlin-output/${module}_test.mjs`, root);
}

async function exports(platform, root) {
  const url = platform === "js" ? js(root, "kiteaudio-output", "output/BrowserPlaybackExport") : wasm(root, "kiteaudio-output");
  return import(url.href);
}

/**
 * { check, again } on [platform]. check(seconds, mode, deviceId) plays, mode being Auto, Worklet or Buffers, on the
 * output with deviceId from the last listDevices of that platform, or on the default output when deviceId is empty,
 * and reports to globalThis.kiteAudioOutputReport; again() starts the last check's output again.
 */
export async function playbackCheck(platform, root) {
  const module = await exports(platform, root);
  return { check: module.kiteAudioOutputCheck, again: module.kiteAudioOutputAgain };
}

/**
 * The outputs that [platform] lists, as [{ id, name, isDefault }]. Each platform keeps its own listing, and a
 * check on that platform can open only a device from it. Rejects when the listing fails.
 */
export async function listDevices(platform, root) {
  const module = await exports(platform, root);
  const text = await new Promise((resolve) => {
    globalThis.kiteAudioOutputDevicesListed = resolve;
    module.kiteAudioOutputDevices();
  });
  const result = JSON.parse(text);
  if (result.error) throw new Error(result.error);
  return result;
}
