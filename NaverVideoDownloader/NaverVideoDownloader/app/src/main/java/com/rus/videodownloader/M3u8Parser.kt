package com.rus.videodownloader

import java.net.URL

/**
 * HLS(m3u8) 재생목록을 파싱하는 유틸리티.
 * - 마스터 재생목록: 여러 화질(variant)의 목록을 담고 있음 (#EXT-X-STREAM-INF)
 * - 미디어 재생목록: 실제 재생 가능한 .ts 세그먼트 목록을 담고 있음 (#EXTINF)
 */
object M3u8Parser {

    data class Variant(
        val bandwidth: Int,
        val width: Int,
        val height: Int,
        val url: String
    )

    /** 마스터 재생목록인지 여부 (하위 화질 목록을 담고 있는지) */
    fun isMasterPlaylist(content: String): Boolean =
        content.contains("#EXT-X-STREAM-INF")

    /** 실제 세그먼트(.ts)를 담은 미디어 재생목록인지 여부 */
    fun isMediaPlaylist(content: String): Boolean =
        content.contains("#EXTINF")

    /**
     * 마스터 재생목록에서 화질별 variant 목록을 추출한다.
     * baseUrl 은 이 m3u8 파일 자체의 URL (상대경로 해석용).
     */
    fun parseVariants(content: String, baseUrl: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        var pendingBandwidth = 0
        var pendingWidth = 0
        var pendingHeight = 0

        val lines = content.lines()
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingBandwidth = Regex("BANDWIDTH=(\\d+)")
                    .find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                pendingWidth = res?.groupValues?.get(1)?.toIntOrNull() ?: 0
                pendingHeight = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                variants.add(
                    Variant(
                        bandwidth = pendingBandwidth,
                        width = pendingWidth,
                        height = pendingHeight,
                        url = resolveUrl(baseUrl, line)
                    )
                )
                pendingBandwidth = 0
                pendingWidth = 0
                pendingHeight = 0
            }
        }
        return variants
    }

    /**
     * 미디어 재생목록에서 실제 세그먼트 URL 목록을 순서대로 추출한다.
     */
    fun parseSegments(content: String, baseUrl: String): List<String> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveUrl(baseUrl, it) }
    }

    /** 최고 해상도(없으면 최고 대역폭) variant 선택 */
    fun pickHighestQuality(variants: List<Variant>): Variant? {
        if (variants.isEmpty()) return null
        return variants.maxWithOrNull(
            compareBy({ it.width.toLong() * it.height.toLong() }, { it.bandwidth })
        )
    }

    private fun resolveUrl(baseUrl: String, ref: String): String {
        return if (ref.startsWith("http://") || ref.startsWith("https://")) {
            ref
        } else {
            URL(URL(baseUrl), ref).toString()
        }
    }
}
