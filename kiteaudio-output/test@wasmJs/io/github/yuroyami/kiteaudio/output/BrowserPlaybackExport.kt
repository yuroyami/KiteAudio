@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalJsExport::class)

package io.github.yuroyami.kiteaudio.output

import kotlin.js.ExperimentalJsExport
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("(text) => { globalThis.kiteAudioOutputReport(text); }")
private external fun reportToPage(text: String)

@JsFun("(text) => { globalThis.kiteAudioOutputDevicesListed(text); }")
private external fun devicesToPage(text: String)

private var again: (() -> Unit)? = null

// The page lists and opens through one listing, as an app would through audioOutputDevices().
private val devices = BrowserOutputDevices(WasmWebAudio)

/**
 * [browserPlaybackCheck] through a browser output that plays by the [BrowserTechnique] named [mode],
 * on the output with [deviceId] from the last [kiteAudioOutputDevices], or on the default output
 * when [deviceId] is empty. Reports to `globalThis.kiteAudioOutputReport`, for verification/web.
 */
@JsExport
public fun kiteAudioOutputCheck(seconds: Int, mode: String, deviceId: String) {
    val device = browserChosenDevice(devices, deviceId)
    again = browserPlaybackCheck(
        seconds,
        { format, frames, onEvent ->
            openBrowserOutput(WasmWebAudio, format, frames, BrowserTechnique.valueOf(mode), device, AudioFormatPolicy.Exact, devices, onEvent)
        },
        ::reportToPage,
    )
}

/** Starts the output of the last [kiteAudioOutputCheck] again, as a click handler would after a start made before the click. */
@JsExport
public fun kiteAudioOutputAgain() {
    again?.invoke()
}

/** [browserDeviceList], reporting to `globalThis.kiteAudioOutputDevicesListed`, for verification/web. */
@JsExport
public fun kiteAudioOutputDevices() {
    browserDeviceList(devices, ::devicesToPage)
}
