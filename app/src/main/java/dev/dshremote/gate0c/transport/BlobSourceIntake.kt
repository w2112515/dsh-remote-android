package dev.dshremote.gate0c.transport

import java.io.InputStream

/** ADR-005 M1 接受的图片媒体类型（与 DSH ImageMediaType 一致）。 */
internal val BLOB_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/** 内容 Uri 的解析结果。 */
internal sealed interface BlobSourceResolution {
    /** 可交给 BlobUploadPipeline.stageAndUpload 的来源。 */
    data class Resolved(
        val openSource: () -> InputStream,
        val displayName: String?,
        val mediaType: String,
    ) : BlobSourceResolution

    /** 用户可读的不可用原因（不支持的类型、不可读、超限）。 */
    data class Unavailable(val detail: String) : BlobSourceResolution
}

/** ContentResolver 的最小 seam：Android 侧薄实现，JVM 测试内存实现。 */
internal interface BlobUriGateway {
    /** 打开内容流；每次调用独立打开（嗅探与上传各开一次）。不可打开返回 null。 */
    fun open(uri: String): InputStream?

    /** 提供方报告的显示名/MIME/声明大小；任一项不可得即 null，绝不编造。 */
    fun describe(uri: String): BlobUriDescription?
}

internal data class BlobUriDescription(
    val displayName: String?,
    val mediaType: String?,
    val declaredSizeBytes: Long?,
)

/**
 * 相册/拍照入口到上传管线的桥：把内容 Uri 解析成诚实的 (openSource,
 * displayName, mediaType) 三元组。提供方报告的 MIME 是受支持图片时直接
 * 采用；缺失或 application/octet-stream 时嗅探魔数复核——非光栅内容在
 * 入口即拒绝，而不是让 Host 在提交时拒收整个已传完的传输。声明大小超限
 * 也在入口拒绝，不先占用暂存。
 */
internal class BlobSourceIntake(private val gateway: BlobUriGateway) {
    fun resolve(uri: String): BlobSourceResolution {
        val description = gateway.describe(uri)
            ?: return BlobSourceResolution.Unavailable("无法读取所选内容")
        description.declaredSizeBytes?.let { declared ->
            if (declared > BlobUploadDeclaration.MAX_BLOB_BYTES) {
                return BlobSourceResolution.Unavailable(
                    "文件超过 ${BlobUploadDeclaration.MAX_BLOB_BYTES / 1_048_576} MiB 上限",
                )
            }
        }
        val reported = description.mediaType?.lowercase()
        val mediaType = when {
            reported != null && reported in BLOB_IMAGE_MEDIA_TYPES -> reported
            reported == null || reported == "application/octet-stream" -> when (val sniffed = sniff(uri)) {
                is Sniff.Matched -> sniffed.mediaType
                Sniff.NoMatch -> return BlobSourceResolution.Unavailable("仅支持 PNG/JPEG/WebP/GIF 图片附件")
                Sniff.Unreadable -> return BlobSourceResolution.Unavailable("无法读取所选内容")
            }
            else -> return BlobSourceResolution.Unavailable("仅支持 PNG/JPEG/WebP/GIF 图片附件")
        }
        return BlobSourceResolution.Resolved(
            openSource = {
                gateway.open(uri) ?: throw java.io.IOException("所选内容不可打开")
            },
            displayName = sanitizeDisplayName(description.displayName),
            mediaType = mediaType,
        )
    }

    private sealed interface Sniff {
        data class Matched(val mediaType: String) : Sniff
        data object NoMatch : Sniff
        data object Unreadable : Sniff
    }

    private fun sniff(uri: String): Sniff {
        val header = try {
            gateway.open(uri)?.use { input ->
                val buffer = ByteArray(SNIFF_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                buffer.copyOf(read)
            } ?: return Sniff.Unreadable
        } catch (_: Exception) {
            return Sniff.Unreadable
        }
        return when {
            header.startsWith(PNG_MAGIC) -> Sniff.Matched("image/png")
            header.startsWith(JPEG_MAGIC) -> Sniff.Matched("image/jpeg")
            header.startsWith(GIF87_MAGIC) || header.startsWith(GIF89_MAGIC) -> Sniff.Matched("image/gif")
            header.startsWith(RIFF_MAGIC) && header.size >= 12 &&
                header.copyOfRange(8, 12).contentEquals(WEBP_MAGIC) -> Sniff.Matched("image/webp")
            else -> Sniff.NoMatch
        }
    }

    private fun sanitizeDisplayName(reported: String?): String? {
        val name = reported
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val bytes = name.encodeToByteArray()
        if (bytes.size <= MAX_DISPLAY_NAME_BYTES) return name
        // 按 UTF-8 边界截断：回退跳过续字节，绝不劈开字符。
        var end = MAX_DISPLAY_NAME_BYTES
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end -= 1
        return bytes.copyOf(end).decodeToString()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val SNIFF_BYTES = 12
        const val MAX_DISPLAY_NAME_BYTES = 512
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val GIF87_MAGIC = "GIF87a".encodeToByteArray()
        val GIF89_MAGIC = "GIF89a".encodeToByteArray()
        val RIFF_MAGIC = "RIFF".encodeToByteArray()
        val WEBP_MAGIC = "WEBP".encodeToByteArray()
    }
}
