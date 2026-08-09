package com.rus.videodownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * m3u8 세그먼트(.ts) 목록을 순서대로 다운로드하여 하나의 파일로 이어붙인다.
 * MPEG-TS 세그먼트는 단순 바이너리 이어붙이기만으로도 대부분의 플레이어(VLC, MX Player 등)에서
 * 재생 가능한 하나의 .ts 파일이 된다.
 *
 * referer / userAgent / cookie 는 WebView 세션과 동일하게 맞춰서 전송해야
 * 네이버 등에서 요청이 차단되지 않는다.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val referer: String,
    private val userAgent: String,
    private val cookie: String?
) {

    suspend fun downloadAndMerge(
        segmentUrls: List<String>,
        outputFile: File,
        onProgress: (done: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            segmentUrls.forEachIndexed { index, segUrl ->
                val bytes = fetchBytes(segUrl)
                if (bytes != null) {
                    out.write(bytes)
                }
                onProgress(index + 1, segmentUrls.size)
            }
        }
    }

    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        val response = client.newCall(buildRequest(url)).execute()
        response.use {
            if (!it.isSuccessful) return@withContext null
            it.body?.string()
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        val response = client.newCall(buildRequest(url)).execute()
        response.use {
            if (!it.isSuccessful) return null
            return it.body?.bytes()
        }
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }
        return builder.build()
    }
}
