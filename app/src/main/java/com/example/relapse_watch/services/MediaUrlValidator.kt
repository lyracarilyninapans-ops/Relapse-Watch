package com.example.relapse_watch.services

import java.net.InetAddress
import java.net.URI

object MediaUrlValidator {

    private val allowedHosts = setOf(
        "firebasestorage.googleapis.com",
        "storage.googleapis.com"
    )

    fun validate(rawUrl: String): Result<URI> = runCatching {
        require(rawUrl.isNotBlank()) { "URL is blank" }

        val uri = URI(rawUrl)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS URLs are allowed" }

        val host = uri.host?.lowercase() ?: error("URL host is missing")
        require(host in allowedHosts) { "Host is not allowlisted: $host" }

        val resolved = InetAddress.getAllByName(host)
        require(resolved.isNotEmpty()) { "Host did not resolve" }
        resolved.forEach { address ->
            require(!address.isAnyLocalAddress) { "Local address is not allowed" }
            require(!address.isLoopbackAddress) { "Loopback address is not allowed" }
            require(!address.isSiteLocalAddress) { "Private network address is not allowed" }
            require(!address.isLinkLocalAddress) { "Link-local address is not allowed" }
            require(!address.isMulticastAddress) { "Multicast address is not allowed" }
        }

        uri
    }
}
