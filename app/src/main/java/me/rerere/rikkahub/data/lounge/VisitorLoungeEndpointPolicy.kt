package me.rerere.rikkahub.data.lounge

import java.net.IDN
import java.net.InetAddress
import java.net.URI

object VisitorLoungeEndpointPolicy {
    fun normalize(rawEndpoint: String): URI {
        val raw = rawEndpoint.trim()
        require(raw.isNotEmpty()) { "Visitor lounge entrance is required" }

        val parsed = runCatching { URI(raw) }.getOrElse {
            throw IllegalArgumentException("Visitor lounge entrance is invalid")
        }
        require(parsed.scheme.equals("https", ignoreCase = true)) {
            "Visitor lounge entrance must use HTTPS"
        }
        require(parsed.userInfo == null) { "Visitor lounge entrance must not include credentials" }
        require(parsed.rawQuery == null) { "Visitor lounge entrance must not include a query" }
        require(parsed.rawFragment == null) { "Visitor lounge entrance must not include a fragment" }
        require(parsed.host != null) { "Visitor lounge entrance must include a host" }

        val host = normalizeHost(parsed.host)
        require(!isLocalHost(host)) { "Visitor lounge entrance must be publicly reachable" }
        val path = parsed.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        return URI("https", null, host, parsed.port, path, null, null).normalize()
    }

    private fun normalizeHost(host: String): String = runCatching {
        IDN.toASCII(host).lowercase()
    }.getOrElse {
        throw IllegalArgumentException("Visitor lounge entrance host is invalid")
    }

    private fun isLocalHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true)) {
            return true
        }
        if (!host.contains(':') && !host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
    }
}

/** Sanitizes text before it can reach local storage, UI, or a normal conversation. */
object VisitorLoungeRedactor {
    private val authorization = Regex(
        pattern = """(?i)Authorization\s*:\s*Bearer\s+[^\s,;]+""",
    )
    private val endpointWithQuery = Regex(
        pattern = """(?i)https://[^\s?#]+(?:/[^\s?#]*)?\?[^\s#]+""",
    )
    private val endpointWithUserInfo = Regex(
        pattern = """(?i)https://[^\s/@]+@[^\s/?#]+(?:/[^\s?#]*)?""",
    )

    fun redact(value: String): String = value
        .replace(endpointWithQuery, "[redacted endpoint query]")
        .replace(endpointWithUserInfo, "[redacted endpoint credentials]")
        .replace(authorization, "Authorization: Bearer [redacted]")
}
