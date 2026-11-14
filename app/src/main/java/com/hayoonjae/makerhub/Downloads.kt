package com.hayoonjae.makerhub

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * 웹뷰는 다운로드를 스스로 못 한다. 세 갈래로 나눠 처리한다.
 * - http(s): DownloadManager (OS 이미지처럼 큰 파일도 알림바에서 이어받기)
 * - blob:  페이지 안에서 fetch -> base64 -> 자바 브리지로 넘겨 저장 (Wokwi 프로젝트 zip, 펌웨어 등)
 * - data:  바로 디코딩해서 저장
 */
class Downloads(private val activity: Activity) {

    val bridge = Bridge()

    fun start(view: WebView, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("blob:") -> view.evaluateJavascript(blobScript(url), null)
            url.startsWith("data:") -> Thread { saveDataUrl(url, null) }.start()
            else -> enqueue(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val name = FileNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType), null)
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$FOLDER/$name")
            }
            activity.getSystemService(DownloadManager::class.java).enqueue(request)
            toast("다운로드 시작: $name")
        } catch (e: Exception) {
            toast("다운로드 실패: ${e.message}")
        }
    }

    private fun saveDataUrl(dataUrl: String, suggestedName: String?) {
        val parsed = DataUrls.parse(dataUrl) ?: return toast("저장 실패: 읽을 수 없는 데이터")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(parsed.mime)
        val name = FileNames.sanitize(suggestedName, ext)
        val where = saveBytes(name, parsed.mime, parsed.bytes)
        toast("저장됨: $where")
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray): String {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("저장 위치를 만들 수 없음")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
        }
        // Android 8~9 는 권한 없이 공용 폴더에 못 쓴다. 앱 전용 다운로드 폴더에 둔다.
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun toast(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    }

    private fun blobScript(url: String) = """
        (function(){
          var name = window.__mhName || '';
          window.__mhName = '';
          fetch(${JSONObject.quote(url)}).then(function(r){ return r.blob(); }).then(function(b){
            var fr = new FileReader();
            fr.onloadend = function(){ $BRIDGE.save(String(fr.result), name); };
            fr.readAsDataURL(b);
          }).catch(function(e){ $BRIDGE.fail(String(e)); });
        })();
    """.trimIndent()

    /** 자바스크립트에서 불린다. 웹뷰의 JavaBridge 스레드라 여기서 디코딩·쓰기를 해도 UI 가 안 멈춘다. */
    inner class Bridge {
        @JavascriptInterface
        fun save(dataUrl: String?, name: String?) {
            if (dataUrl == null) return
            runCatching { saveDataUrl(dataUrl, name) }.onFailure { toast("저장 실패: ${it.message}") }
        }

        @JavascriptInterface
        fun fail(message: String?) {
            toast("다운로드 실패: $message")
        }
    }

    companion object {
        const val BRIDGE = "MakerHubSaver"
        private const val FOLDER = "MakerHub"

        /**
         * blob 다운로드는 파일 이름이 안 넘어온다. <a download="이름"> 클릭 순간의 이름을 기억해 둔다.
         * FileSaver 류는 문서에 안 붙인 a 태그에 .click() 을 부르므로 프로토타입도 감싼다.
         */
        val HOOK_JS = """
            (function(){
              if (window.__mhHooked) return; window.__mhHooked = true;
              function remember(a){ if (a && a.download) window.__mhName = a.download; }
              var orig = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){ remember(this); return orig.apply(this, arguments); };
              document.addEventListener('click', function(e){
                var t = e.target; remember(t && t.closest ? t.closest('a[download]') : null);
              }, true);
            })();
        """.trimIndent()
    }
}
