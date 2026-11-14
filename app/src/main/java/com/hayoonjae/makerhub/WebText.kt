package com.hayoonjae.makerhub

import java.io.ByteArrayOutputStream
import java.util.Base64

object UserAgents {
    /**
     * 웹뷰 기본 UA 에서 "; wv" 와 "Version/4.0" 을 뺀다. 이게 붙어 있으면 구글 로그인이
     * "안전하지 않은 브라우저" 로 막고, 일부 사이트가 앱 내장 브라우저 전용 화면을 띄운다.
     */
    fun mobile(default: String): String =
        default.replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")

    /** 데스크톱 사이트 보기용. 크롬 버전은 실제 웹뷰 버전을 그대로 쓴다. */
    fun desktop(default: String): String {
        val chrome = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "140.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }
}

object FileNames {
    private val illegal = Regex("[\\u0000-\\u001f\"*:<>?|\\\\/]")

    fun sanitize(raw: String?, fallbackExt: String?): String {
        var name = (raw ?: "").replace('\\', '/').substringAfterLast('/')
            .replace(illegal, "_")
            .trim()
            .trimStart('.')
        if (name.isEmpty()) name = "download"
        if (!name.contains('.') && !fallbackExt.isNullOrEmpty()) name += ".$fallbackExt"
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "").take(10)
            name = if (ext.isEmpty()) name.take(120) else name.take(119 - ext.length) + "." + ext
        }
        return name
    }
}

class DataUrl(val mime: String, val bytes: ByteArray)

object DataUrls {
    fun parse(url: String): DataUrl? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val mime = meta.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val bytes = if (meta.endsWith(";base64")) {
            runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull() ?: return null
        } else {
            percentDecode(payload)
        }
        return DataUrl(mime, bytes)
    }

    /** URLDecoder 는 '+' 를 공백으로 바꿔서 쓰면 안 된다. */
    private fun percentDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            val b = c.toString().toByteArray(Charsets.UTF_8)
            out.write(b, 0, b.size)
            i++
        }
        return out.toByteArray()
    }
}
