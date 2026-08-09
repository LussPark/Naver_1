package com.rus.videodownloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rus.videodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)

    // 페이지 로드 중 감청된 m3u8 URL 목록 (중복 제거, 발견 순서 유지)
    private val detectedPlaylists = LinkedHashSet<String>()

    private var currentArticleUrl: String = ""

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.mediaPlaybackRequiresUserGesture = false

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (url.contains(".m3u8", ignoreCase = true)) {
                    runOnUiThread { onPlaylistDetected(url) }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.loadButton.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "URL을 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startLoad(url)
        }

        binding.downloadButton.setOnClickListener {
            downloadBest()
        }
    }

    private fun startLoad(url: String) {
        currentArticleUrl = url
        detectedPlaylists.clear()
        binding.logText.text = ""
        binding.statusText.text = "상태: 페이지 로딩 중..."
        binding.downloadButton.isEnabled = false
        binding.webView.loadUrl(url)
    }

    private fun onPlaylistDetected(url: String) {
        if (detectedPlaylists.add(url)) {
            appendLog("m3u8 감지: $url")
            binding.statusText.text = "상태: 스트림 ${detectedPlaylists.size}개 감지됨"
            binding.downloadButton.isEnabled = true
        }
    }

    private fun appendLog(line: String) {
        binding.logText.append("$line\n")
    }

    private fun downloadBest() {
        if (detectedPlaylists.isEmpty()) {
            Toast.makeText(this, "감지된 스트림이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        binding.downloadButton.isEnabled = false
        binding.statusText.text = "상태: 재생목록 분석 중..."

        val cookie = CookieManager.getInstance().getCookie(currentArticleUrl)
        val userAgent = binding.webView.settings.userAgentString
        val downloader = SegmentDownloader(httpClient, currentArticleUrl, userAgent, cookie)

        scope.launch {
            try {
                // 감지된 재생목록 중 가장 나중에 발견된 것부터 확인 (보통 실제 재생에 쓰인 것이 마지막)
                val candidates = detectedPlaylists.toList().reversed()
                var mediaPlaylistUrl: String? = null
                var mediaPlaylistContent: String? = null

                for (candidateUrl in candidates) {
                    val content = downloader.fetchText(candidateUrl) ?: continue

                    if (M3u8Parser.isMasterPlaylist(content)) {
                        val variants = M3u8Parser.parseVariants(content, candidateUrl)
                        val best = M3u8Parser.pickHighestQuality(variants)
                        if (best != null) {
                            appendLog("최고화질 선택: ${best.width}x${best.height} (${best.bandwidth} bps)")
                            val variantContent = downloader.fetchText(best.url)
                            if (variantContent != null && M3u8Parser.isMediaPlaylist(variantContent)) {
                                mediaPlaylistUrl = best.url
                                mediaPlaylistContent = variantContent
                                break
                            }
                        }
                    } else if (M3u8Parser.isMediaPlaylist(content)) {
                        mediaPlaylistUrl = candidateUrl
                        mediaPlaylistContent = content
                        break
                    }
                }

                if (mediaPlaylistUrl == null || mediaPlaylistContent == null) {
                    binding.statusText.text = "상태: 재생 가능한 세그먼트 목록을 찾지 못함"
                    binding.downloadButton.isEnabled = true
                    return@launch
                }

                val segments = M3u8Parser.parseSegments(mediaPlaylistContent, mediaPlaylistUrl)
                appendLog("세그먼트 ${segments.size}개 다운로드 시작")
                binding.statusText.text = "상태: 다운로드 중 (0/${segments.size})"
                binding.progressBar.max = segments.size
                binding.progressBar.progress = 0

                val fileName = "video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())}.ts"
                val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val outputFile = File(outputDir, fileName)

                downloader.downloadAndMerge(segments, outputFile) { done, total ->
                    runOnUiThread {
                        binding.progressBar.progress = done
                        binding.statusText.text = "상태: 다운로드 중 ($done/$total)"
                    }
                }

                binding.statusText.text = "상태: 완료 -> ${outputFile.absolutePath}"
                appendLog("저장 완료: ${outputFile.absolutePath}")
                Toast.makeText(this@MainActivity, "다운로드 완료", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.statusText.text = "상태: 오류 - ${e.message}"
                appendLog("오류: ${e.message}")
            } finally {
                binding.downloadButton.isEnabled = true
            }
        }
    }
}
