package com.hayoonjae.makerhub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun siteHomesAndSubdomainsStayInApp() {
        listOf(
            "https://www.raspberrypi.com/",
            "https://www.raspberrypi.com/documentation/computers/getting-started.html",
            "https://forums.raspberrypi.com/viewforum.php?f=63",
            "https://datasheets.raspberrypi.com/pico/pico-datasheet.pdf",
            "https://connect.raspberrypi.com/sign-in",
            "https://id.raspberrypi.com/sign-in?client=connect",
            "https://www.raspberrypi.org/",
            "https://www.arduino.cc/",
            "https://docs.arduino.cc/hardware/uno-r4-wifi/",
            "https://app.arduino.cc/sketches",
            "https://login.arduino.cc/login",
            "https://store.arduino.cc/products/arduino-uno-rev3",
            "https://wokwi.com/",
            "https://wokwi.com/projects/new/arduino-uno",
            "https://www.hmd.com/en_int/all-phones",
            "https://www.hmd.com/en_int/hmd-skyline",
            "http://RaspberryPi.com",
            "https://user@forums.raspberrypi.com:443/t?x#y",
        ).forEach { assertEquals(it, Route.IN_APP, UrlPolicy.route(it)) }
    }

    @Test
    fun lookalikeAndOtherSitesGoToBrowser() {
        listOf(
            "https://raspberrypi.com.evil.example/",
            "https://notarduino.cc/",
            "https://github.com/raspberrypi/linux",
            "https://www.youtube.com/watch?v=abc",
            "https://www.google.com/search?q=pico",
        ).forEach { assertEquals(it, Route.BROWSER, UrlPolicy.route(it)) }
    }

    @Test
    fun signInHopsStayInApp() {
        listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x",
            "https://github.com/login/oauth/authorize?client_id=x",
            "https://github.com/sessions/two-factor",
            "https://appleid.apple.com/auth/authorize",
            "https://www.facebook.com/v19.0/dialog/oauth?client_id=x",
            "https://wokwi-prod.firebaseapp.com/__/auth/handler?x=1",
        ).forEach { assertEquals(it, Route.IN_APP, UrlPolicy.route(it)) }
    }

    @Test
    fun nonWebSchemes() {
        assertEquals(Route.SYSTEM, UrlPolicy.route("mailto:hello@example.com"))
        assertEquals(Route.SYSTEM, UrlPolicy.route("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(Route.WEBVIEW, UrlPolicy.route("about:blank"))
        assertEquals(Route.WEBVIEW, UrlPolicy.route("blob:https://wokwi.com/1234"))
        assertEquals(Route.WEBVIEW, UrlPolicy.route("data:text/plain,hi"))
    }

    @Test
    fun hostAndPathParsing() {
        assertEquals("forums.raspberrypi.com" to "/t/1", UrlPolicy.hostAndPath("https://u@Forums.RaspberryPi.com:443/t/1?x#y"))
        assertEquals("wokwi.com" to "/", UrlPolicy.hostAndPath("https://wokwi.com?x=1"))
        assertNull(UrlPolicy.hostAndPath("https:wokwi.com"))
    }

    @Test
    fun userAgentDropsWebViewMarkers() {
        val ua = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.51 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.51 Mobile Safari/537.36",
            UserAgents.mobile(ua),
        )
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.7339.51 Safari/537.36",
            UserAgents.desktop(ua),
        )
    }

    @Test
    fun fileNames() {
        assertEquals("sketch.ino", FileNames.sanitize("sketch.ino", "txt"))
        assertEquals("project.zip", FileNames.sanitize("project", "zip"))
        assertEquals("download.bin", FileNames.sanitize("", "bin"))
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd", null))
        assertEquals("a_b.txt", FileNames.sanitize("a:b.txt", null))
        assertEquals(120, FileNames.sanitize("x".repeat(300) + ".uf2", null).length)
        assert(FileNames.sanitize("x".repeat(300) + ".uf2", null).endsWith(".uf2"))
    }

    @Test
    fun dataUrls() {
        val b64 = DataUrls.parse("data:application/zip;base64,UEsDBA==")!!
        assertEquals("application/zip", b64.mime)
        assertArrayEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04), b64.bytes)
        val plain = DataUrls.parse("data:,a+b%20c%")!!
        assertEquals("application/octet-stream", plain.mime)
        assertEquals("a+b c%", String(plain.bytes))
        assertNull(DataUrls.parse("data:nocomma"))
    }
}
