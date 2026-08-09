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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rus.videodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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

    private var currentArticleUrl: String = ""

    // 페이지에서 추출한 기사 제목 (파일명으로 사용)
    private var currentArticleTitle: String = ""

    // 페이지에서 추출한 기사 날짜, "YYMMDD" 형식으로 정규화해서 저장
    private var currentArticleDatePrefix: String = ""

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
                triggerAutoDownloadOnce("자동 배속 재생 완료 감지")
            }
        }

        @JavascriptInterface
        fun onVideoFound() {
            runOnUiThread {
                appendLog("영상 요소 발견, 배속 자동 재생 시작")
            }
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
                    // 1) 재생목록 파일 자체가 잡히는 경우 (가장 이상적인 경우) - 즉시 다운로드 가능
                    pathOnly.endsWith(".m3u8") -> {
                        runOnUiThread { onPlaylistDetected(url) }
                    }
                    // 2) 단일 mp4 직접 링크 - 즉시 다운로드 가능
                    pathOnly.endsWith(".mp4") -> {
                        runOnUiThread { onPlaylistDetected(url) }
                    }
                    // 3) 재생목록 없이 개별 .ts 세그먼트만 보이는 경우 (네이버TV 인라인 영상 등)
                    //    전체 개수를 알 수 없으므로 재생이 끝날 때까지 기다렸다가 다운로드한다.
                    pathOnly.endsWith(".ts") -> {
                        runOnUiThread { onSegmentDetected(url) }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                extractArticleMeta(view)
                view.evaluateJavascript(AUTO_PLAY_SCRIPT, null)
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
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return // API 29+는 MediaStore로 권한 없이 저장 가능
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
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
                val obj = org.json.JSONObject(jsonString)
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
     * 지원 형식: "20260808182000" (og:regDate), "2026-08-08T18:20:00+09:00" (ISO 8601),
     * 그 외 문자열 안에 포함된 "2026.08.08" 또는 "2026-08-08" 패턴.
     */
    private fun parseToYyMmDd(raw: String): String? {
        if (raw.isBlank()) return null

        // 1) YYYYMMDDHHMMSS (숫자 14자리, og:regDate 형식)
        Regex("^(\\d{4})(\\d{2})(\\d{2})\\d{0,6}$").find(raw.trim())?.let { m ->
            val (yyyy, mm, dd) = m.destructured
            return yyyy.takeLast(2) + mm + dd
        }

        // 2) YYYY-MM-DD 또는 YYYY.MM.DD 패턴이 문자열 안에 포함된 경우 (ISO 8601 포함)
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
            // m3u8/mp4는 그 자체로 완결된 다운로드 대상이므로 감지 즉시 자동 다운로드한다.
            triggerAutoDownloadOnce("재생목록/mp4 감지")
        }
    }

    /**
     * m3u8 없이 개별 .ts 세그먼트만 보이는 경우, 같은 폴더에 속한 세그먼트끼리 그룹으로 묶고
     * 파일명 끝의 숫자(순번)를 추출해 정렬 기준으로 사용한다.
     * 예: .../hls/f94f8130-...-000005.ts -> 그룹 ".../hls", 순번 5
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
            appendLog("세그먼트 #$seq 감지 (그룹 누적 ${map.size}개)")
            binding.statusText.text = "상태: 세그먼트 ${map.size}개 수집 중 (재생 종료 시 자동 다운로드)"
            binding.downloadButton.isEnabled = true
            // 세그먼트는 전체 개수를 알 수 없으므로 여기서는 자동 다운로드하지 않고
            // JsBridge.onVideoEnded() 신호를 기다린다.
        }
    }

    /** 자동 다운로드를 한 번만 트리거한다. */
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
                // 감지된 항목 중 가장 나중에 발견된 것부터 확인 (보통 실제 재생에 쓰인 것이 마지막)
                val candidates = detectedPlaylists.toList().reversed()

                // 1) MP4 직접 링크가 있으면 그것부터 우선 시도 (세그먼트 병합 불필요, 가장 단순)
                val mp4Url = candidates.firstOrNull { it.lowercase().contains(".mp4") }
                if (mp4Url != null) {
                    downloadSingleMp4(downloader, mp4Url)
                    return@launch
                }

                // 2) m3u8(HLS) 재생목록이 감지된 경우 정석대로 처리
                val m3u8Result = tryDownloadFromMasterOrMediaPlaylist(downloader, candidates)
                if (m3u8Result) {
                    return@launch
                }

                // 3) m3u8도 mp4도 없다면, 직접 수집된 .ts 세그먼트 그룹을 사용한다.
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

    /** m3u8 마스터/미디어 재생목록을 파싱해 최고화질로 다운로드. 성공 시 true, 대상이 없으면 false. */
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

    /** m3u8 없이 수집된 .ts 그룹 중 세그먼트 수가 가장 많은 그룹을 골라 순번대로 다운로드한다. */
    private suspend fun downloadFromRawSegments(downloader: SegmentDownloader) {
        val bestGroup = segmentGroups.maxByOrNull { it.value.size }?.value
        if (bestGroup == null) {
            binding.statusText.text = "상태: 세그먼트 그룹을 찾지 못함"
            binding.downloadButton.isEnabled = true
            return
        }
        val orderedUrls = bestGroup.toSortedMap().values.toList()
        appendLog("재생목록(m3u8) 없이 세그먼트 ${orderedUrls.size}개로 병합 시도")
        downloadSegmentList(downloader, orderedUrls)
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

    /**
     * 임시로 받은 파일을 공개 Movies 폴더(MediaStore)로 옮긴다.
     * Android 10 이상은 권한 없이 MediaStore로 바로 등록되고, 갤러리/파일관리자에 즉시 노출된다.
     * Android 9 이하는 실제 공개 Movies 디렉터리에 직접 파일을 쓰고 MediaStore에 등록한다.
     * 만약 저장소 접근이 실패하면(권한 거부 등) 앱 전용 폴더에 보관하는 것으로 대체한다.
     */
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
            // 실패 시 앱 전용 폴더에라도 남겨둔다 (최소한 데이터 유실은 방지)
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
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
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
                // Android 9 이하: 권한이 없으면 실패로 처리 (호출부에서 앱 전용 폴더로 대체)
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
     * "YYMMDD_기사제목.확장자" 형태의 최종 표시 파일명을 만든다.
     * 기사 날짜를 못 가져온 경우 오늘 날짜를 대신 사용하고,
     * 제목을 못 가져온 경우 시간 기반 이름으로 대체한다.
     * 동일 이름이 이미 Movies 폴더에 있으면 MediaStore가 자동으로 구분해 저장한다.
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
        return "${datePrefix}_$titlePart.$extension"
    }

    private fun sanitizeFileName(rawTitle: String): String {
        var cleaned = rawTitle.trim()

        // 네이버 뉴스 제목에 흔히 붙는 매체/사이트 접미사 제거 (예: "... - 네이버 뉴스")
        cleaned = cleaned.replace(Regex("\\s*[-|:]\\s*네이버\\s*(뉴스|TV)?\\s*$"), "")

        // 파일명으로 쓸 수 없는 문자 치환
        cleaned = cleaned.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        // 너무 길면 자르기 (파일시스템 경로 길이 제한 대비)
        if (cleaned.length > 80) {
            cleaned = cleaned.substring(0, 80).trim()
        }

        return cleaned.ifBlank { "video_${timestamp()}" }
    }

    companion object {
        // 페이지 로드 완료 후 주입되는 스크립트.
        // <video> 요소를 찾아 음소거 + 4배속 자동 재생시키고, 끝에 도달하면 Android로 신호를 보낸다.
        private const val AUTO_PLAY_SCRIPT = """
            (function() {
                var triggered = false;
                function notifyEnded() {
                    if (triggered) return;
                    triggered = true;
                    if (window.AndroidBridge) { AndroidBridge.onVideoEnded(); }
                }
                function setup(video) {
                    if (video.__autoDlSetup) return;
                    video.__autoDlSetup = true;
                    if (window.AndroidBridge) { AndroidBridge.onVideoFound(); }
                    video.muted = true;
                    try { video.playbackRate = 4.0; } catch (e) {}
                    var playPromise = video.play();
                    if (playPromise && playPromise.catch) { playPromise.catch(function(e) {}); }
                    video.addEventListener('ended', notifyEnded);
                    video.addEventListener('timeupdate', function() {
                        if (video.duration && !isNaN(video.duration) &&
                            video.currentTime >= video.duration - 0.5) {
                            notifyEnded();
                        }
                    });
                }
                function scan() {
                    var video = document.querySelector('video');
                    if (video) { setup(video); return; }
                    setTimeout(scan, 500);
                }
                scan();
                // 동적으로 늦게 삽입되는 플레이어 대응 (최대 30초 관찰)
                var observer = new MutationObserver(function() {
                    var video = document.querySelector('video');
                    if (video) { setup(video); }
                });
                observer.observe(document.documentElement, { childList: true, subtree: true });
                setTimeout(function() { observer.disconnect(); }, 30000);
            })();
        """
    }
}
