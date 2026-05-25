package com.pearlnode.data.api

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

// Handles both Basic (Gen1) and SHA-256 Digest (Gen2) auth
class ShellyAuthenticator(
    private val username: String,
    private val password: String,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("Authorization") != null) return null // already tried

        val challenge = response.header("WWW-Authenticate") ?: return null

        return when {
            challenge.startsWith("Digest", ignoreCase = true) ->
                response.request.newBuilder()
                    .header("Authorization", buildDigestHeader(challenge, response.request))
                    .build()

            challenge.startsWith("Basic", ignoreCase = true) ->
                response.request.newBuilder()
                    .header("Authorization", Credentials.basic(username, password))
                    .build()

            else -> null
        }
    }

    private fun buildDigestHeader(challenge: String, request: Request): String {
        val params = parseDigestChallenge(challenge)
        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"]
        val algorithm = params["algorithm"] ?: "MD5"
        val uri = request.url.encodedPath + if (request.url.encodedQuery != null) "?${request.url.encodedQuery}" else ""
        val method = request.method
        val nc = "00000001"
        val cnonce = SecureRandom().let { rng ->
            ByteArray(8).also { rng.nextBytes(it) }
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }

        val ha1 = if (algorithm.equals("SHA-256", ignoreCase = true)) {
            sha256("$username:$realm:$password")
        } else {
            md5("$username:$realm:$password")
        }
        val ha2 = if (algorithm.equals("SHA-256", ignoreCase = true)) {
            sha256("$method:$uri")
        } else {
            md5("$method:$uri")
        }

        val responseHash = if (qop != null) {
            if (algorithm.equals("SHA-256", ignoreCase = true)) {
                sha256("$ha1:$nonce:$nc:$cnonce:${qop.split(",").first().trim()}:$ha2")
            } else {
                md5("$ha1:$nonce:$nc:$cnonce:${qop.split(",").first().trim()}:$ha2")
            }
        } else {
            if (algorithm.equals("SHA-256", ignoreCase = true)) {
                sha256("$ha1:$nonce:$ha2")
            } else {
                md5("$ha1:$nonce:$ha2")
            }
        }

        return buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            append(", algorithm=$algorithm")
            if (qop != null) {
                append(", qop=${qop.split(",").first().trim()}, nc=$nc, cnonce=\"$cnonce\"")
            }
            append(", response=\"$responseHash\"")
        }
    }

    private fun parseDigestChallenge(challenge: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val body = challenge.substringAfter(" ")
        val regex = Regex("""(\w+)="?([^",]+)"?""")
        regex.findAll(body).forEach { match ->
            map[match.groupValues[1]] = match.groupValues[2]
        }
        return map
    }

    private fun md5(input: String): String = hash("MD5", input)
    private fun sha256(input: String): String = hash("SHA-256", input)

    private fun hash(algorithm: String, input: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }
}
