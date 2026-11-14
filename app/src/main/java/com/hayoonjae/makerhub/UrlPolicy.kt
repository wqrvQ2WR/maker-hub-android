package com.hayoonjae.makerhub

import java.util.Locale

enum class Route {
    /** 앱 웹뷰 안에서 연다. */
    IN_APP,

    /** 외부 브라우저로 넘긴다. */
    BROWSER,

    /** mailto:, tel:, intent: 같은 건 시스템에 맡긴다. */
    SYSTEM,

    /** about:, blob:, data: 는 웹뷰가 알아서 처리하게 둔다. */
    WEBVIEW,
}

object UrlPolicy {

    /** 이 도메인과 모든 하위 도메인(forums., datasheets., docs., app., id. ...)은 앱 안에서 연다. */
    val siteDomains = listOf("raspberrypi.com", "raspberrypi.org", "arduino.cc", "wokwi.com", "hmd.com")

    /**
     * 로그인·결제 중간에 잠깐 거쳐 가는 곳. 여기를 외부 브라우저로 넘기면 로그인 쿠키가
     * 앱으로 안 돌아와서 로그인이 끝나지 않는다. 그래서 해당 경로만 앱 안에서 연다.
     */
    private val passThrough: List<(host: String, path: String) -> Boolean> = listOf(
        { h, _ -> h == "accounts.google.com" || h == "accounts.youtube.com" },
        { h, p -> h == "github.com" && (p.startsWith("/login") || p.startsWith("/session")) },
        { h, _ -> h == "appleid.apple.com" },
        { h, p -> (h == "www.facebook.com" || h == "m.facebook.com") && (p.startsWith("/login") || "/dialog/oauth" in p) },
        { h, _ -> h == "login.microsoftonline.com" },
        { h, p -> matchesDomain(h, "firebaseapp.com") && p.startsWith("/__/auth/") },
        { h, _ -> h == "www.paypal.com" || h == "checkout.stripe.com" || h == "hooks.stripe.com" },
    )

    fun route(url: String): Route {
        val colon = url.indexOf(':')
        if (colon <= 0) return Route.WEBVIEW
        return when (url.substring(0, colon).lowercase(Locale.ROOT)) {
            "http", "https" -> {
                val (host, path) = hostAndPath(url, colon) ?: return Route.BROWSER
                if (isInApp(host, path)) Route.IN_APP else Route.BROWSER
            }
            "about", "blob", "data", "javascript" -> Route.WEBVIEW
            else -> Route.SYSTEM
        }
    }

    /** 사용자가 직접 넣은 주소(lmphone 터널·LAN 주소)의 호스트. 앱 안에서 연다. */
    val ownHosts = LinkedHashSet<String>()

    fun isInApp(host: String, path: String): Boolean =
        siteDomains.any { matchesDomain(host, it) } || host in ownHosts || passThrough.any { it(host, path) }

    fun matchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /** "https://user@Forums.RaspberryPi.com:443/t/1?x#y" -> ("forums.raspberrypi.com", "/t/1") */
    fun hostAndPath(url: String, colon: Int = url.indexOf(':')): Pair<String, String>? {
        if (!url.startsWith("//", colon + 1)) return null
        val start = colon + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
        val host = url.substring(start, authorityEnd)
            .substringAfterLast('@')
            .substringBefore(':')
            .trimEnd('.')
            .lowercase(Locale.ROOT)
        if (host.isEmpty()) return null
        val rest = url.substring(authorityEnd)
        val path = rest.substringBefore('?').substringBefore('#').ifEmpty { "/" }
        return host to path
    }
}
