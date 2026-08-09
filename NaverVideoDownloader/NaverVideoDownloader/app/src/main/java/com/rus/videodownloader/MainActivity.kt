package com.rus.videodownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rus.videodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)

    // m3u8/mp4로 명확히 식별된 항목 (URL 그대로 저장, 발견 순서 유지)
    private val detectedPlaylists = LinkedHashSet<String>()

    // m3u8 재생목록이 감지되지 않는 사이트를 대비해, 개별 .ts 세그먼트를 직접 수집한다.
    // key: 세그먼트가 속한 폴더 경로(그룹), value: (순번 -> URL)
    private val segmentGroups = LinkedHashMap<String, MutableMap<Int, String>>()

    // 네이버TV류 플레이어가 내부적으로 호출하는 화질 목록 메타데이터 API URL (rmcnmv 도메인)
    private var capturedApiManifestUrl: String? = null

    private var currentArticleUrl: String = ""

    // 페이지에서 추출한 기사 제목 (파일명으로 사용)
    private var currentArticleTitle: String = ""

    // 페이지에서 추출한 기사 날짜, "YYMMDD" 형식으로 정규화해서 저장
    private var currentArticleDatePrefix: String = ""

    // 최종적으로 선택/추정된 화질 라벨 (예: "1024p"), 파일명 끝에 붙인다
    private var selectedQualityLabel: String = ""

    // 자동 다운로드가 중복으로 여러 번 트리거되는 것을 방지
    private var autoDownloadTriggered = false

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Android 9 이하 기기에서 공개 저장소에 쓰기 위한 런타임 권한 요청
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                appendLog("저장소 권한이 거부되어, 다운로드는 앱 전용 폴더에만 저장됩니다.")
            }
        }

    /** WebView 안의 JS가 Kotlin으로 신호를 보내기 위한 다리 역할 */
    inner class JsBridge {
        @JavascriptInterface
        fun onVideoEnded() {
            runOnUiThread {
                triggerAutoDownloadOnce("재생 완료 감지")
            }
        }

        @JavascriptInterface
        fun onVideoFound() {
            runOnUiThread {
                appendLog("영상 요소 발견. 재생 버튼을 누르면 화질 선택 팝업이 뜹니다.")
            }
        }

        /** 사용자가 재생을 누른 직후, 페이지(DOM)에서 수집한 화질 옵션 목록(JSON 문자열 배열)을 전달받는다. */
        @JavascriptInterface
        fun onQualityOptionsFound(optionsJson: String) {
            runOnUiThread { handleQualitySignal(optionsJson) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"

        requestLegacyStoragePermissionIfNeeded()

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.mediaPlaybackRequiresUserGesture = false
        binding.webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val lower = url.lowercase()
                val pathOnly = lower.substringBefore("?")

                when {
                    // 0) 네이버TV류 플레이어의 화질 목록 메타데이터 API (JSON, rmcnmv 도메인)
                    lower.contains("rmcnmv") && (lower.contains("vod") || lower.contains("play")) -> {
                        runOnUiThread { onApiManifestDetected(url) }
                    }
                    // 1) 재생목록 파일 자체가 잡히는 경우 - 즉시 다운로드 가능
                    pathOnly.endsWith(".m3u8") -> {
                        runOnUiThread { onPlaylistDetected(url) }
                    }
                    // 2) 단일 mp4 직접 링크 - 즉시 다운로드 가능
                    pathOnly.endsWith(".mp4") -> {
                        runOnUiThread { onPlaylistDetected(url) }
                    }
                    // 3) 재생목록 없이 개별 .ts 세그먼트만 보이는 경우 (네이버TV 인라인 영상 등)
                    pathOnly.endsWith(".ts") -> {
                        runOnUiThread { onSegmentDetected(url) }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                extractArticleMeta(view)
                view.evaluateJavascript(SETUP_SCRIPT, null)
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

        binding.copyLogButton.setOnClickListener {
            copyLogToClipboard()
        }
    }

    /** Android 9(API 28) 이하에서만 필요한 레거시 저장소 쓰기 권한을 요청한다. */
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun onApiManifestDetected(url: String) {
        if (capturedApiManifestUrl == null) {
            capturedApiManifestUrl = url
            appendLog("메타데이터 API 감지: $url")
        }
    }

    /**
     * 재생 버튼을 누른 뒤 화질을 결정한다.
     * 1순위: 감지된 메타데이터 API를 직접 조회해 서버가 제공하는 실제 화질 목록/URL을 사용한다.
     * 2순위: API가 없거나 파싱 실패 시, 페이지 DOM에서 스캔한 화질 옵션 텍스트를 사용한다.
     */
    private fun handleQualitySignal(domOptionsJson: String) {
        val apiUrl = capturedApiManifestUrl
        if (apiUrl == null) {
            showDomQualityPickerDialog(domOptionsJson)
            return
        }

        appendLog("메타데이터 API에서 화질 목록 조회 중...")
        scope.launch {
            val apiOptions = try {
                fetchQualityOptionsFromApi(apiUrl)
            } catch (e: Exception) {
                appendLog("API 조회 실패: ${e.message}")
                emptyMap()
            }

            if (apiOptions.isNotEmpty()) {
                showApiQualityPickerDialog(apiOptions)
            } else {
                appendLog("API에서 화질 정보를 찾지 못함, 페이지 화질 메뉴로 시도합니다.")
                showDomQualityPickerDialog(domOptionsJson)
            }
        }
    }

    /** 메타데이터 API의 JSON 응답에서 (해상도 -> 다운로드 URL) 후보를 모두 찾는다. 스키마를 가정하지 않고 재귀적으로 탐색한다. */
    private suspend fun fetchQualityOptionsFromApi(url: String): Map<Int, String> {
        val cookie = CookieManager.getInstance().getCookie(currentArticleUrl)
        val userAgent = binding.webView.settings.userAgentString
        val downloader = SegmentDownloader(httpClient, currentArticleUrl, userAgent, cookie)
        val body = downloader.fetchText(url) ?: return emptyMap()

        val results = mutableMapOf<Int, String>()
        try {
            val root = JSONTokener(body).nextValue()
            collectQualityCandidates(root, results)
        } catch (e: Exception) {
            appendLog("API 응답 파싱 실패: ${e.message}")
        }
        return results
    }

    /** JSON 구조를 재귀적으로 훑어서 (url/source 필드 + width·height 또는 화질명) 조합을 찾는다. */
    private fun collectQualityCandidates(node: Any?, results: MutableMap<Int, String>) {
        when (node) {
            is JSONObject -> {
                val url = node.optString("source", "").ifBlank { node.optString("url", "") }
                if (url.startsWith("http")) {
                    val height = node.optInt("height", -1)
                    val resolution = if (height > 0) {
                        height
                    } else {
                        val nameField = node.optString("name", "").ifBlank { node.optString("id", "") }
                        Regex("(\\d{3,4})").find(nameField)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    }
                    if (resolution > 0 && !results.containsKey(resolution)) {
                        results[resolution] = url
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectQualityCandidates(node.opt(keys.next()), results)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectQualityCandidates(node.opt(i), results)
                }
            }
        }
    }

    private fun showApiQualityPickerDialog(options: Map<Int, String>) {
        val sortedHeights = options.keys.sortedDescending()
        val labels = sortedHeights.map { "${it}p" }
        appendLog("API 화질 옵션 감지: ${labels.joinToString(", ")}")

        AlertDialog.Builder(this)
            .setTitle("다운로드할 화질을 선택하세요")
            .setItems(labels.toTypedArray()) { _, which ->
                val height = sortedHeights[which]
                val url = options.getValue(height)
                selectedQualityLabel = "${height}p"
                autoDownloadTriggered = true
                downloadDirectQualityUrl(url)
            }
            .setCancelable(false)
            .show()
    }

    private fun showDomQualityPickerDialog(optionsJson: String) {
        val options = try {
            val arr = JSONArray(optionsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }

        if (options.isEmpty()) {
            appendLog("화질 옵션을 찾지 못함, 기본 화질로 재생합니다.")
            resumeDefaultPlayback()
            return
        }

        val sorted = options.distinct().sortedByDescending { extractLeadingNumber(it) ?: -1 }
        appendLog("페이지 화질 옵션 감지: ${sorted.joinToString(", ")}")

        AlertDialog.Builder(this)
            .setTitle("다운로드할 화질을 선택하세요")
            .setItems(sorted.toTypedArray()) { _, which ->
                selectQualityAndPlay(sorted[which])
            }
            .setCancelable(false)
            .show()
    }

    private fun extractLeadingNumber(text: String): Int? =
        Regex("(\\d{3,4})").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** API에서 얻은 직접 URL을 그대로 다운로드한다 (mp4면 바로, m3u8이면 재생목록을 풀어서). */
    private fun downloadDirectQualityUrl(url: String) {
        binding.downloadButton.isEnabled = false
        binding.statusText.text = "상태: $selectedQualityLabel 다운로드 준비 중..."

        val cookie = CookieManager.getInstance().getCookie(currentArticleUrl)
        val userAgent = binding.webView.settings.userAgentString
        val downloader = SegmentDownloader(httpClient, currentArticleUrl, userAgent, cookie)

        scope.launch {
            try {
                val pathOnly = url.substringBefore("?").lowercase()
                if (pathOnly.endsWith(".m3u8")) {
                    val content = downloader.fetchText(url)
                    when {
                        content != null && M3u8Parser.isMasterPlaylist(content) -> {
                            val variants = M3u8Parser.parseVariants(content, url)
                            val best = M3u8Parser.pickHighestQuality(variants)
                            val variantContent = best?.let { downloader.fetchText(it.url) }
                            if (best != null && variantContent != null) {
                                val segments = M3u8Parser.parseSegments(variantContent, best.url)
                                downloadSegmentList(downloader, segments)
                            } else {
                                binding.statusText.text = "상태: 재생목록 처리 실패"
                                binding.downloadButton.isEnabled = true
                            }
                        }
                        content != null && M3u8Parser.isMediaPlaylist(content) -> {
                            val segments = M3u8Parser.parseSegments(content, url)
                            downloadSegmentList(downloader, segments)
                        }
                        else -> {
                            binding.statusText.text = "상태: 재생목록을 불러오지 못함"
                            binding.downloadButton.isEnabled = true
                        }
                    }
                } else {
                    appendLog("직접 다운로드: $url")
                    val displayName = buildDisplayName("mp4")
                    val tempFile = File(cacheDir, "staging_${System.currentTimeMillis()}.mp4")
                    downloader.downloadAndMerge(listOf(url), tempFile) { _, _ ->
                        runOnUiThread { binding.statusText.text = "상태: $selectedQualityLabel 다운로드 중" }
                    }
                    finishDownload(tempFile, displayName, "video/mp4")
                }
            } catch (e: Exception) {
                binding.statusText.text = "상태: 오류 - ${e.message}"
                appendLog("오류: ${e.message}")
                binding.downloadButton.isEnabled = true
            }
        }
    }

    /** DOM에서 스캔한 화질 옵션을 웹뷰에서 클릭시키고, 그 화질로만 재생을 시작한다 (API 실패 시 백업 경로). */
    private fun selectQualityAndPlay(qualityText: String) {
        appendLog("선택한 화질: $qualityText -> 해당 화질로 재생 시작")

        segmentGroups.clear()
        detectedPlaylists.clear()
        autoDownloadTriggered = false
        selectedQualityLabel = qualityText.lowercase().replace(" ", "")

        val escaped = qualityText.replace("\\", "\\\\").replace("'", "\\'")
        val clickScript = "window.__naverDlSelectQuality && window.__naverDlSelectQuality('$escaped');"
        binding.webView.evaluateJavascript(clickScript) {
            binding.webView.evaluateJavascript(RESUME_PLAYBACK_SCRIPT, null)
        }
        binding.statusText.text = "상태: $qualityText 화질로 재생 중 (수집 대기)"
    }

    private fun resumeDefaultPlayback() {
        binding.webView.evaluateJavascript(RESUME_PLAYBACK_SCRIPT, null)
    }

    /** og:title / 발행일 메타 태그를 읽어 기사 제목과 날짜를 가져온다. */
    private fun extractArticleMeta(view: WebView) {
        val script = """
            (function() {
                var result = { title: '', date: '' };

                var og = document.querySelector('meta[property="og:title"]');
                result.title = (og && og.content) ? og.content : document.title;

                var regDate = document.querySelector('meta[property="og:regDate"]');
                if (regDate && regDate.content) {
                    result.date = regDate.content;
                } else {
                    var pub = document.querySelector('meta[property="article:published_time"]');
                    if (pub && pub.content) {
                        result.date = pub.content;
                    } else {
                        var itemDate = document.querySelector('meta[itemprop="datePublished"]');
                        if (itemDate && itemDate.content) {
                            result.date = itemDate.content;
                        } else {
                            var timeEl = document.querySelector('.media_end_head_info_datestamp_time');
                            if (timeEl) {
                                result.date = timeEl.getAttribute('data-date-time') || timeEl.textContent || '';
                            }
                        }
                    }
                }

                return JSON.stringify(result);
            })();
        """
        view.evaluateJavascript(script) { rawResult ->
            try {
                val jsonString = JSONTokener(rawResult ?: "").nextValue() as? String ?: return@evaluateJavascript
                val obj = JSONObject(jsonString)
                val title = obj.optString("title", "")
                val date = obj.optString("date", "")

                if (title.isNotBlank()) {
                    currentArticleTitle = title
                    appendLog("기사 제목 인식: $title")
                }

                val yymmdd = parseToYyMmDd(date)
                if (yymmdd != null) {
                    currentArticleDatePrefix = yymmdd
                    appendLog("기사 날짜 인식: $yymmdd")
                }
            } catch (e: Exception) {
                // 메타 정보 파싱 실패는 치명적이지 않으므로 조용히 무시
            }
        }
    }

    /**
     * 다양한 형식의 날짜 문자열을 "YYMMDD" 형태로 정규화한다.
     */
    private fun parseToYyMmDd(raw: String): String? {
        if (raw.isBlank()) return null

        Regex("^(\\d{4})(\\d{2})(\\d{2})\\d{0,6}$").find(raw.trim())?.let { m ->
            val (yyyy, mm, dd) = m.destructured
            return yyyy.takeLast(2) + mm + dd
        }

        Regex("(\\d{4})[-.](\\d{2})[-.](\\d{2})").find(raw)?.let { m ->
            val (yyyy, mm, dd) = m.destructured
            return yyyy.takeLast(2) + mm + dd
        }

        return null
    }

    private fun copyLogToClipboard() {
        val logContent = binding.logText.text.toString()
        if (logContent.isBlank()) {
            Toast.makeText(this, "복사할 로그가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("영상 다운로더 로그", logContent))
        Toast.makeText(this, "로그를 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun startLoad(url: String) {
        currentArticleUrl = url
        currentArticleTitle = ""
        currentArticleDatePrefix = ""
        selectedQualityLabel = ""
        capturedApiManifestUrl = null
        detectedPlaylists.clear()
        segmentGroups.clear()
        autoDownloadTriggered = false
        binding.logText.text = ""
        binding.statusText.text = "상태: 페이지 로딩 중..."
        binding.downloadButton.isEnabled = false
        binding.webView.loadUrl(url)
    }

    private fun onPlaylistDetected(url: String) {
        if (detectedPlaylists.add(url)) {
            appendLog("재생목록/영상 URL 감지: $url")
            binding.statusText.text = "상태: 스트림 ${detectedPlaylists.size}개 감지됨"
            binding.downloadButton.isEnabled = true
            triggerAutoDownloadOnce("재생목록/mp4 감지")
        }
    }

    /**
     * m3u8 없이 개별 .ts 세그먼트만 보이는 경우, 같은 폴더에 속한 세그먼트끼리 그룹으로 묶는다.
     */
    private fun onSegmentDetected(url: String) {
        val pathOnly = url.substringBefore("?")
        val groupKey = pathOnly.substringBeforeLast("/")

        val seqMatch = Regex("-(\\d+)\\.ts$").find(pathOnly)
            ?: Regex("(\\d+)\\.ts$").find(pathOnly)
        val seq = seqMatch?.groupValues?.get(1)?.toIntOrNull()

        if (seq == null) {
            appendLog("세그먼트 감지(순번 인식 실패): $url")
            return
        }

        val map = segmentGroups.getOrPut(groupKey) { mutableMapOf() }
        if (!map.containsKey(seq)) {
            map[seq] = url
            appendLog("세그먼트 #$seq 감지 (누적 ${map.size}개)")
            binding.statusText.text = "상태: 세그먼트 ${map.size}개 수집 중 (재생 종료 시 자동 다운로드)"
            binding.downloadButton.isEnabled = true
        }
    }

    private fun triggerAutoDownloadOnce(reason: String) {
        if (autoDownloadTriggered) return
        autoDownloadTriggered = true
        appendLog("$reason -> 자동 다운로드 시작")
        downloadBest()
    }

    private fun appendLog(line: String) {
        binding.logText.append("$line\n")
    }

    private fun downloadBest() {
        if (detectedPlaylists.isEmpty() && segmentGroups.isEmpty()) {
            Toast.makeText(this, "감지된 스트림이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        binding.downloadButton.isEnabled = false
        binding.statusText.text = "상태: 분석 중..."

        val cookie = CookieManager.getInstance().getCookie(currentArticleUrl)
        val userAgent = binding.webView.settings.userAgentString
        val downloader = SegmentDownloader(httpClient, currentArticleUrl, userAgent, cookie)

        scope.launch {
            try {
                val candidates = detectedPlaylists.toList().reversed()

                val mp4Url = candidates.firstOrNull { it.lowercase().contains(".mp4") }
                if (mp4Url != null) {
                    downloadSingleMp4(downloader, mp4Url)
                    return@launch
                }

                val m3u8Result = tryDownloadFromMasterOrMediaPlaylist(downloader, candidates)
                if (m3u8Result) {
                    return@launch
                }

                if (segmentGroups.isNotEmpty()) {
                    downloadFromRawSegments(downloader)
                    return@launch
                }

                binding.statusText.text = "상태: 재생 가능한 영상을 찾지 못함"
                binding.downloadButton.isEnabled = true
            } catch (e: Exception) {
                binding.statusText.text = "상태: 오류 - ${e.message}"
                appendLog("오류: ${e.message}")
                binding.downloadButton.isEnabled = true
            }
        }
    }

    private suspend fun downloadSingleMp4(downloader: SegmentDownloader, mp4Url: String) {
        appendLog("MP4 직접 다운로드: $mp4Url")
        binding.statusText.text = "상태: MP4 다운로드 중..."
        val displayName = buildDisplayName("mp4")
        val tempFile = File(cacheDir, "staging_${System.currentTimeMillis()}.mp4")

        downloader.downloadAndMerge(listOf(mp4Url), tempFile) { _, _ ->
            runOnUiThread { binding.statusText.text = "상태: MP4 다운로드 중" }
        }

        finishDownload(tempFile, displayName, "video/mp4")
    }

    private suspend fun tryDownloadFromMasterOrMediaPlaylist(
        downloader: SegmentDownloader,
        candidates: List<String>
    ): Boolean {
        var mediaPlaylistUrl: String? = null
        var mediaPlaylistContent: String? = null

        for (candidateUrl in candidates) {
            val content = downloader.fetchText(candidateUrl) ?: continue

            if (M3u8Parser.isMasterPlaylist(content)) {
                val variants = M3u8Parser.parseVariants(content, candidateUrl)
                val best = M3u8Parser.pickHighestQuality(variants)
                if (best != null) {
                    appendLog("최고화질 선택: ${best.width}x${best.height} (${best.bandwidth} bps)")
                    if (best.height > 0) selectedQualityLabel = "${best.height}p"
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
            return false
        }

        val segments = M3u8Parser.parseSegments(mediaPlaylistContent, mediaPlaylistUrl)
        downloadSegmentList(downloader, segments)
        return true
    }

    private suspend fun downloadFromRawSegments(downloader: SegmentDownloader) {
        if (segmentGroups.isEmpty()) {
            binding.statusText.text = "상태: 세그먼트 그룹을 찾지 못함"
            binding.downloadButton.isEnabled = true
            return
        }

        val bestEntry = segmentGroups.entries.maxWithOrNull(
            compareBy(
                { extractQualityHint(it.key) ?: -1 },
                { it.value.size }
            )
        )

        val bestGroup = bestEntry?.value
        if (bestGroup == null) {
            binding.statusText.text = "상태: 세그먼트 그룹을 찾지 못함"
            binding.downloadButton.isEnabled = true
            return
        }

        val qualityHint = bestEntry.key.let { extractQualityHint(it) }
        if (qualityHint != null) {
            selectedQualityLabel = "${qualityHint}p"
        }

        if (segmentGroups.size > 1) {
            appendLog("감지된 전체 그룹 수: ${segmentGroups.size}개 (그룹별 세그먼트: ${segmentGroups.values.map { it.size }})")
        }

        val orderedUrls = bestGroup.toSortedMap().values.toList()
        appendLog("세그먼트 ${orderedUrls.size}개로 병합 시도")
        downloadSegmentList(downloader, orderedUrls)
    }

    private fun extractQualityHint(urlOrKey: String): Int? {
        Regex("(\\d{3,4})[pP](?:[_/.?]|$)").find(urlOrKey)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("[_-](\\d{3,4})[_-]").findAll(urlOrKey)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 240..4320 }
            .maxOrNull()?.let { return it }
        return null
    }

    private suspend fun downloadSegmentList(downloader: SegmentDownloader, segments: List<String>) {
        if (segments.isEmpty()) {
            binding.statusText.text = "상태: 다운로드할 세그먼트가 없음"
            binding.downloadButton.isEnabled = true
            return
        }

        appendLog("세그먼트 ${segments.size}개 다운로드 시작")
        binding.statusText.text = "상태: 다운로드 중 (0/${segments.size})"
        binding.progressBar.max = segments.size
        binding.progressBar.progress = 0

        val displayName = buildDisplayName("ts")
        val tempFile = File(cacheDir, "staging_${System.currentTimeMillis()}.ts")

        downloader.downloadAndMerge(segments, tempFile) { done, total ->
            runOnUiThread {
                binding.progressBar.progress = done
                binding.statusText.text = "상태: 다운로드 중 ($done/$total)"
            }
        }

        finishDownload(tempFile, displayName, "video/mp2t")
    }

    private suspend fun finishDownload(tempFile: File, displayName: String, mimeType: String) {
        val publicUri = try {
            saveToPublicMovies(tempFile, displayName, mimeType)
        } catch (e: Exception) {
            appendLog("공개 Movies 폴더 저장 실패: ${e.message}")
            null
        }

        if (publicUri != null) {
            tempFile.delete()
            binding.statusText.text = "상태: 완료 -> Movies/$displayName"
            appendLog("저장 완료 (갤러리에서 확인 가능): Movies/$displayName")
        } else {
            val fallbackDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val fallbackFile = File(fallbackDir, displayName)
            tempFile.copyTo(fallbackFile, overwrite = true)
            tempFile.delete()
            binding.statusText.text = "상태: 완료(앱 전용 폴더) -> ${fallbackFile.absolutePath}"
            appendLog("저장 완료(앱 전용 폴더): ${fallbackFile.absolutePath}")
        }

        Toast.makeText(this@MainActivity, "다운로드 완료", Toast.LENGTH_LONG).show()
        binding.downloadButton.isEnabled = true
    }

    private suspend fun saveToPublicMovies(sourceFile: File, displayName: String, mimeType: String): Uri? {
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(collection, values) ?: return@withContext null

                contentResolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: return@withContext null

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) return@withContext null

                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val destFile = File(moviesDir, displayName)
                sourceFile.copyTo(destFile, overwrite = true)

                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.DATA, destFile.absolutePath)
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())

    /**
     * "YYMMDD_기사제목_해상도.확장자" 형태의 최종 표시 파일명을 만든다.
     * 해상도는 API/DOM/세그먼트 URL 등에서 확인된 경우에만 붙는다.
     */
    private fun buildDisplayName(extension: String): String {
        val datePrefix = currentArticleDatePrefix.ifBlank {
            SimpleDateFormat("yyMMdd", Locale.KOREA).format(Date())
        }
        val titlePart = if (currentArticleTitle.isNotBlank()) {
            sanitizeFileName(currentArticleTitle)
        } else {
            "video_${timestamp()}"
        }
        val qualitySuffix = if (selectedQualityLabel.isNotBlank()) "_$selectedQualityLabel" else ""
        return "${datePrefix}_$titlePart$qualitySuffix.$extension"
    }

    private fun sanitizeFileName(rawTitle: String): String {
        var cleaned = rawTitle.trim()
        cleaned = cleaned.replace(Regex("\\s*[-|:]\\s*네이버\\s*(뉴스|TV)?\\s*$"), "")
        cleaned = cleaned.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length > 80) {
            cleaned = cleaned.substring(0, 80).trim()
        }
        return cleaned.ifBlank { "video_${timestamp()}" }
    }

    companion object {
        // 페이지 로드 완료 후 주입되는 초기화 스크립트.
        // document 레벨 캡처링으로 play/ended 이벤트를 감지해, video 요소가 나중에 생성/교체되어도 놓치지 않는다.
        private const val SETUP_SCRIPT = """
            (function() {
                if (window.__naverDlAutoInit) return;
                window.__naverDlAutoInit = true;

                var triggered = false;
                var popupShown = false;
                var videoSeen = false;

                function notifyEnded() {
                    if (triggered) return;
                    triggered = true;
                    if (window.AndroidBridge) { AndroidBridge.onVideoEnded(); }
                }

                function tryClickSettingsButton() {
                    var selectors = [
                        '[class*="quality"]', '[class*="setting"]', '[class*="config"]',
                        '[aria-label*="화질"]', '[aria-label*="설정"]', '[title*="화질"]', '[title*="설정"]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el && el.offsetParent !== null) { el.click(); return true; }
                    }
                    return false;
                }

                function collectQualityOptionTexts() {
                    var nodes = document.querySelectorAll('li, button, a, span, div');
                    var set = {};
                    var list = [];
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        if (!el || el.offsetParent === null) continue;
                        var text = (el.textContent || '').trim();
                        if (/^\d{3,4}\s*[pP]$/.test(text) && !set[text]) {
                            set[text] = true;
                            list.push(text);
                        }
                    }
                    return list;
                }

                window.__naverDlSelectQuality = function(text) {
                    var nodes = document.querySelectorAll('li, button, a, span, div');
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        if (!el || el.offsetParent === null) continue;
                        var t = (el.textContent || '').trim();
                        if (t === text) { el.click(); return true; }
                    }
                    return false;
                };

                function handlePlay(video) {
                    if (popupShown) return;
                    popupShown = true;
                    try { video.pause(); } catch (e) {}

                    var opened = tryClickSettingsButton();
                    setTimeout(function() {
                        var options = collectQualityOptionTexts();
                        if (window.AndroidBridge) {
                            AndroidBridge.onQualityOptionsFound(JSON.stringify(options));
                        }
                    }, opened ? 400 : 100);
                }

                document.addEventListener('play', function(e) {
                    var video = e.target;
                    if (!video || video.tagName !== 'VIDEO') return;
                    if (!videoSeen) {
                        videoSeen = true;
                        if (window.AndroidBridge) { AndroidBridge.onVideoFound(); }
                    }
                    handlePlay(video);
                }, true);

                document.addEventListener('ended', function(e) {
                    var video = e.target;
                    if (!video || video.tagName !== 'VIDEO') return;
                    notifyEnded();
                }, true);

                document.addEventListener('timeupdate', function(e) {
                    var video = e.target;
                    if (!video || video.tagName !== 'VIDEO') return;
                    if (video.duration && !isNaN(video.duration) &&
                        video.currentTime >= video.duration - 0.5) {
                        notifyEnded();
                    }
                }, true);

                function scanForDiagnostics() {
                    var video = document.querySelector('video');
                    if (video && !videoSeen) {
                        videoSeen = true;
                        if (window.AndroidBridge) { AndroidBridge.onVideoFound(); }
                        return;
                    }
                    if (!video) { setTimeout(scanForDiagnostics, 500); }
                }
                scanForDiagnostics();
            })();
        """

        // 화질 선택 이후 실제 다운로드용 재생을 시작하는 스크립트 (DOM 백업 경로에서만 사용).
        private const val RESUME_PLAYBACK_SCRIPT = """
            (function() {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = true;
                    try { v.playbackRate = 4.0; } catch (e) {}
                    var p = v.play();
                    if (p && p.catch) { p.catch(function(e) {}); }
                }
            })();
        """
    }
}
