package dev.nanovoice.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Pairing with a QR code (docs/PROTOCOL.md, "Abbinamento con un QR"). The QR holds a one-time code, not the token: the
 * app trades the code for the token with one request and keeps only the address and the token.
 */
sealed interface PairingLinkResult {
    data class Valid(val server: String, val code: String) : PairingLinkResult
    /** Not a NanoVoice pairing code at all. */
    data object NotPairing : PairingLinkResult
    /** A pairing code in a format this version does not know. */
    data object UnsupportedVersion : PairingLinkResult
    /** The server address in it is not one the app accepts (for example `ws://` on the open internet). */
    data class BadServer(val reason: String) : PairingLinkResult
}

object PairingLink {
    /** 16 characters of Crockford base32 (no I, L, O, U): 80 bits. */
    private val CODE = Regex("^[0-9A-HJKMNP-TV-Z]{16}$")
    const val VERSION = "1"

    fun parse(text: String): PairingLinkResult {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return PairingLinkResult.NotPairing
        if (uri.scheme != "nanovoice" || uri.host != "pair") return PairingLinkResult.NotPairing
        val query = uri.rawQuery?.split('&')?.mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.orEmpty().toMap()
        if (query["v"] != VERSION) return PairingLinkResult.UnsupportedVersion
        val code = query["code"]?.uppercase()?.replace(Regex("[\\s-]"), "")
        if (code == null || !CODE.matches(code)) return PairingLinkResult.NotPairing
        val server = query["server"] ?: return PairingLinkResult.NotPairing
        return when (val check = ServerUrl.check(server)) {
            is ServerUrlCheck.Valid -> PairingLinkResult.Valid(check.url, code)
            is ServerUrlCheck.Invalid -> PairingLinkResult.BadServer(check.reason)
        }
    }
}

sealed interface PairingResult {
    data class Paired(val token: String) : PairingResult
    /** Wrong, expired or already used: the server says the same for all three. */
    data object InvalidCode : PairingResult
    data object TooManyAttempts : PairingResult
    /** The server could not be reached or answered something unexpected. */
    data object Unreachable : PairingResult
    /** A ws:// server and no VPN: the token would travel in clear on this network, so nothing was sent. */
    data object NeedsVpn : PairingResult
}

/** Trades a pairing code for the token. The code is never logged and nothing is kept of it. */
class PairingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun exchange(server: String, code: String, device: String): PairingResult {
        val http = server.trim().replaceFirst(Regex("^(?i)ws"), "http").trimEnd('/')
        val body = buildJsonObject { put("code", code); put("device", device.take(40)) }.toString()
        val request = runCatching {
            Request.Builder().url("$http/pair").post(body.toRequestBody("application/json".toMediaType())).build()
        }.getOrNull() ?: return PairingResult.Unreachable
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        // A small answer only: a token is a few dozen characters.
                        val text = response.body?.source()?.let { it.request(MAX_ANSWER + 1L); it.buffer.snapshot().utf8() }.orEmpty()
                        if (text.length > MAX_ANSWER) return PairingResult.Unreachable
                        val token = runCatching { Json.parseToJsonElement(text).jsonObject["token"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull }.getOrNull()
                        if (token != null && ServerUrl.tokenIsValid(token)) PairingResult.Paired(token) else PairingResult.Unreachable
                    }
                    401 -> PairingResult.InvalidCode
                    429 -> PairingResult.TooManyAttempts
                    else -> PairingResult.Unreachable
                }
            }
        } catch (_: java.io.IOException) {
            PairingResult.Unreachable
        }
    }

    private companion object {
        const val MAX_ANSWER = 4096
    }
}
