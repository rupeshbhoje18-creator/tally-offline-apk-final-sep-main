package com.example.tallycustomerapp

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.tallycustomerapp.data.AppDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class OfflinePageActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PAGE_ID = "page_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = false
        webView.settings.domStorageEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.scheme?.lowercase()
                if (url == "http" || url == "https") {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        val pageId = intent.getLongExtra(EXTRA_PAGE_ID, -1L)
        if (pageId <= 0L) {
            finish()
            return
        }

        Thread {
            val page = AppDatabase.getDatabase(this).offlineDao().getPageSnapshot(pageId)
            val html = page?.let { gunzip(it.htmlGzip) }
            runOnUiThread {
                if (html == null) {
                    finish()
                } else {
                    title = page.title.ifBlank { "Offline Tally Page" }
                    webView.loadDataWithBaseURL(
                        null,
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        }.start()
    }

    private fun gunzip(bytes: ByteArray): String {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
