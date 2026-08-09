package com.rus.videodownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    // 그룹별로 "가장 최근에 세그먼트를 받은 시점"의 순번을 기록한다.
    // 사용자가 재생 중 화질을 바꾸면 새 그룹이 생기고, 이전 그룹은 더 이상 갱신되지 않으므로
    // 이 값이 가장 큰 그룹 = 현재 실제로 재생 중인(마지막으로 선택된) 화질이다.
    private val groupLastSeenOrder = mutableMapOf<String, Int>()
    private var segmentSeq = 0

    // 화질을 바꿔도 URL 폴더 경로가 동일한 사이트를 위한 보조 장치.
    // 세그먼트 순번이 갑자기 뒤로 튀면(예: 30 -> 0) 재생이 처음부터 다시 시작된 것으로 보고
    // 같은 폴더라도 새로운 세션(=새로운 화질 시도)으로 취급한다.
    private var currentSessionId = 0
    private var lastMaxSeqInSession = -1

    // 마지막으로 세그먼트가 감지된 시각 (ms). 아직 재생 중인데 수동으로 성급하게
    // 다운로드 버튼을 눌러 영상이 잘리는 것을 막기 위한 안전장치로 사용한다.
    private var lastSegmentReceivedAt = 0L

    private var currentArticleUrl: String = ""

    // 페이지에서 추출한 기사 제목 (파일명으로 사용)
    private var currentArticleTitle: String = ""

    // 페이지에서 추출한 기사 날짜, "YYMMDD" 형식으로 정규화해서 저장
    private var currentArticleDatePrefix: String = ""

    // 다운로드 완료 후 실제 파일을 분석해 측정한 화질 라벨 (예: "760p"), 파일명 끝에 붙인다
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
                // 화질 전환은 세그먼트 순번 리셋으로 세션 단위로 추적되므로,
                // 재생이 완전히 끝나는 시점(=현재 선택된 화질의 마지막 세그먼트까지 도착한 시점)에
                // 자동으로 다운로드해도 안전하다.
                triggerAutoDownloadOnce("재생 완료 감지")
            }
        }

        @JavascriptInterface
        fun onVideoFound() {
            runOnUiThread {
                appendLog("영상 요소 발견. 재생 후 화질(톱니바퀴) 버튼으로 원하는 화질을 고르세요. 끝까지 재생되면 자동으로 다운로드됩니다.")
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
                    // 1) 재생목록 파일 자체가 잡히는 경우 - 즉시 다운로드 가능
                    pathOnly.endsWith(".m3u8") -> {
                        runOnUiThread { onPlaylistDetected(url) }
                    }
                    // 2) 단일 mp4 직접 링크 - 즉시 다운로드 가능
                    pathOnly.endsWith(".mp4") -> {
                        runOnUiThread { onPlaylistDetected(url) }
                    }
                    // 3) 재생목록 없이 개별 .ts 세그먼트만 보이는 경우 (네이버TV 인라인 영상 등)
                    //    사용자가 사이트 자체 화질 메뉴로 화질을 바꾸면 그룹이 바뀌며 계속 최신 그룹을 추적한다.
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
            val elapsedSinceLastSegment = System.currentTimeMillis() - lastSegmentReceivedAt
            if (segmentGroups.isNotEmpty() && lastSegmentReceivedAt > 0 && elapsedSinceLastSegment < 1500) {
                Toast.makeText(
                    this,
                    "아직 재생 중인 것 같습니다. 영상이 끝날 때까지 기다린 뒤 다시 눌러주세요.",
                    Toast.LENGTH_LONG
                ).show()
                appendLog("다운로드 보류: 최근 ${elapsedSinceLastSegment}ms 전에도 세그먼트가 들어와 아직 재생 중으로 판단")
            } else {
                downloadBest()
            }
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
        detectedPlaylists.clear()
        segmentGroups.clear()
        groupLastSeenOrder.clear()
        segmentSeq = 0
        currentSessionId = 0
        lastMaxSeqInSession = -1
        lastSegmentReceivedAt = 0L
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
     * m3u8 없이 개별 .ts 세그먼트만 보이는 경우, 세그먼트끼리 그룹으로 묶는다.
     * 1차 기준: "세그먼트 번호만 {seq}로 치환한 전체 URL(쿼리 포함)" — 화질이 폴더가 아니라
     *          쿼리 파라미터(비트레이트/화질 토큰 등)로만 구분되는 사이트도 커버한다.
     * 2차 보강: 그래도 URL이 완전히 동일하게 유지되는 사이트를 대비해, 세그먼트 순번이
     *          갑자기 뒤로 튀면(예: 30 -> 0, 재생이 처음부터 다시 시작됨) 같은 URL 패턴이라도
     *          새로운 세션(그룹)으로 취급한다.
     */
    private fun onSegmentDetected(url: String) {
        val seqMatch = Regex("-(\\d+)\\.ts(?:\\?|$)").find(url)
            ?: Regex("(\\d+)\\.ts(?:\\?|$)").find(url)
        val seq = seqMatch?.groupValues?.get(1)?.toIntOrNull()

        if (seqMatch == null || seq == null) {
            appendLog("세그먼트 감지(순번 인식 실패): $url")
            return
        }

        // 순번이 뒤로 크게 튀면(예: 30 -> 0) 화질 전환 등으로 재생이 새로 시작된 것으로 간주
        if (lastMaxSeqInSession >= 0 && seq < lastMaxSeqInSession - 2) {
            currentSessionId++
            lastMaxSeqInSession = -1
            appendLog("순번 리셋 감지 -> 화질이 바뀐 것으로 보고 새 세션(#$currentSessionId) 시작")
        }
        lastMaxSeqInSession = maxOf(lastMaxSeqInSession, seq)
        lastSegmentReceivedAt = System.currentTimeMillis()

        val seqDigitsRange = seqMatch.range.first.let { start ->
            val digits = seqMatch.groupValues[1]
            val digitStart = url.indexOf(digits, start)
            digitStart until (digitStart + digits.length)
        }
        val urlPattern = url.replaceRange(seqDigitsRange, "{seq}")
        val groupKey = "$urlPattern#session$currentSessionId"

        val map = segmentGroups.getOrPut(groupKey) { mutableMapOf() }
        val isNew = !map.containsKey(seq)
        if (isNew) {
            map[seq] = url
        }
        // 새 세그먼트든 아니든, 이 그룹이 방금 활동했다는 사실을 기록 (최신 활동 그룹 판별용)
        groupLastSeenOrder[groupKey] = segmentSeq++

        if (isNew) {
            appendLog("세그먼트 #$seq 감지 (그룹#$currentSessionId 누적 ${map.size}개, 전체 그룹 ${segmentGroups.size}개)")
            binding.statusText.text = "상태: 세그먼트 수집 중 (원하는 화질로 재생 후 다운로드 버튼을 누르세요)"
            binding.downloadButton.isEnabled = true
        }
    }

    private fun triggerAutoDownloadOnce(reason: String) {
        if (autoDownloadTriggered) return
        appendLog("$reason -> 자동 다운로드 시작")
        downloadBest()
    }

    private fun appendLog(line: String) {
        binding.logText.append("$line\n")
    }

    private fun downloadBest() {
        if (autoDownloadTriggered) {
            appendLog("이미 다운로드가 시작되어 중복 실행을 건너뜁니다.")
            return
        }
        autoDownloadTriggered = true

        if (detectedPlaylists.isEmpty() && segmentGroups.isEmpty()) {
            Toast.makeText(this, "감지된 스트림이 없습니다", Toast.LENGTH_SHORT).show()
            autoDownloadTriggered = false
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
        val tempFile = File(cacheDir, "staging_${System.currentTimeMillis()}.mp4")

        downloader.downloadAndMerge(listOf(mp4Url), tempFile) { _, _ ->
            runOnUiThread { binding.statusText.text = "상태: MP4 다운로드 중" }
        }

        finishDownloadWithMeasurement(tempFile, "mp4", "video/mp4")
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

    /** 가장 최근에 세그먼트가 도착한 그룹(=현재 재생/선택 중인 화질)을 다운로드한다. */
    private suspend fun downloadFromRawSegments(downloader: SegmentDownloader) {
        if (segmentGroups.isEmpty()) {
            binding.statusText.text = "상태: 세그먼트 그룹을 찾지 못함"
            binding.downloadButton.isEnabled = true
            return
        }

        val bestKey = groupLastSeenOrder.entries.maxByOrNull { it.value }?.key
            ?: segmentGroups.keys.last()
        val bestGroup = segmentGroups[bestKey]

        if (bestGroup == null) {
            binding.statusText.text = "상태: 세그먼트 그룹을 찾지 못함"
            binding.downloadButton.isEnabled = true
            return
        }

        if (segmentGroups.size > 1) {
            appendLog("감지된 전체 그룹 수: ${segmentGroups.size}개 (최근 활동 그룹의 세그먼트 ${bestGroup.size}개 사용)")
        }

        val orderedUrls = bestGroup.toSortedMap().values.toList()
        appendLog("세그먼트 ${orderedUrls.size}개로 병합 시도")
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

        val tempFile = File(cacheDir, "staging_${System.currentTimeMillis()}.ts")

        downloader.downloadAndMerge(segments, tempFile) { done, total ->
            runOnUiThread {
                binding.progressBar.progress = done
                binding.statusText.text = "상태: 다운로드 중 ($done/$total)"
            }
        }

        finishDownloadWithMeasurement(tempFile, "ts", "video/mp2t")
    }

    /**
     * 다운로드가 끝난 임시 파일의 실제 해상도를 측정한 뒤 최종 파일명을 정하고 저장한다.
     * URL 문자열 추측이 아니라 실제 파일을 분석하므로 항상 정확하다.
     */
    private suspend fun finishDownloadWithMeasurement(tempFile: File, extension: String, mimeType: String) {
        val measuredHeight = detectActualResolution(tempFile)
        selectedQualityLabel = if (measuredHeight != null && measuredHeight > 0) {
            appendLog("실제 다운로드 해상도 측정: ${measuredHeight}p")
            "${measuredHeight}p"
        } else {
            appendLog("해상도 측정 실패 (파일명에 화질 정보 생략)")
            ""
        }

        val displayName = buildDisplayName(extension)
        finishDownload(tempFile, displayName, mimeType)
    }

    /** MediaMetadataRetriever로 실제 영상 파일의 세로 해상도를 측정한다. 회전 정보를 반영해 보정한다. */
    private suspend fun detectActualResolution(file: File): Int? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) width else height
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
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
     * 해상도는 다운로드된 실제 파일을 측정해 확인된 경우에만 붙는다.
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
        // 화질을 강제로 바꾸거나 재생을 가로채지 않는다. 사용자가 사이트 자체 화질 메뉴로
        // 직접 원하는 화질(예: 1024p)을 선택할 시간을 충분히 준다.
        // document 레벨 캡처링으로 play/ended 이벤트를 감지해, video 요소가 나중에 생성/교체되어도 놓치지 않는다.
        private const val SETUP_SCRIPT = """
            (function() {
                if (window.__naverDlAutoInit) return;
                window.__naverDlAutoInit = true;

                var triggered = false;
                var videoSeen = false;

                function notifyEnded() {
                    if (triggered) return;
                    triggered = true;
                    if (window.AndroidBridge) { AndroidBridge.onVideoEnded(); }
                }

                document.addEventListener('play', function(e) {
                    var video = e.target;
                    if (!video || video.tagName !== 'VIDEO') return;
                    if (!videoSeen) {
                        videoSeen = true;
                        if (window.AndroidBridge) { AndroidBridge.onVideoFound(); }
                    }
                    try { video.muted = true; } catch (e) {}
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
    }
}
