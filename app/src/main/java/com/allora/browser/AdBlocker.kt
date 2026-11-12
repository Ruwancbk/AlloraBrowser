package com.allora.browser

import android.net.Uri

/**
 * Simple built-in blocker for well-known ad / pop-up / redirect networks.
 * (A full uBlock Origin extension comes in the next step.)
 */
object AdBlocker {

    private val blockedDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
        "exosrv.com", "juicyads.com", "adsterra.com", "hilltopads.com",
        "clickadu.com", "trafficjunky.com", "adcash.com", "ad-maven.com",
        "admaven.com", "onclickads.net", "onclasrv.com", "tsyndicate.com",
        "popunder.net", "adnxs.com", "adsrvr.org", "taboola.com",
        "outbrain.com", "revcontent.com", "mgid.com", "yllix.com",
        "a-ads.com", "ero-advertising.com", "trafficstars.com",
        "realsrv.com", "syndication.realsrv.com", "mc.yandex.ru",
        "bidvertiser.com", "adf.ly", "shorte.st", "linkbucks.com",
        "pushwhy.com", "notifpush.com", "richpush.co", "clickaine.com",
        "acint.net", "adskeeper.com", "smartadserver.com", "criteo.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "moatads.com",
        "scorecardresearch.com", "amazon-adsystem.com", "2mdn.net"
    )

    private val blockedHostPrefixes = listOf("ads.", "adserver.", "adserv.", "popunder.", "popads.")

    fun isBlocked(uri: String): Boolean {
        val host = try {
            Uri.parse(uri).host
        } catch (e: Exception) {
            null
        } ?: return false
        val h = host.lowercase()
        if (blockedHostPrefixes.any { h.startsWith(it) }) return true
        return blockedDomains.any { h == it || h.endsWith(".$it") }
    }
}
