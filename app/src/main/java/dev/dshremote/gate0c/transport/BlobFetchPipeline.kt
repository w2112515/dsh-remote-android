package dev.dshremote.gate0c.transport

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** ADR-005 获取源：时间线图片（session-log-reference ACL）或截断产物全文（Host 内部解析 id→路径）。 */
internal sealed interface BlobFetchSource {
    /** ACL 证明所用的会话 id（Host 校验该会话引用了此 blob）。 */
    val sessionId: String

    data class Attachment(val attachmentId: String, override val sessionId: String) : BlobFetchSource

    data class Artifact(val artifactId: String, override val sessionId: String) : BlobFetchSource
}

/**
 * 下载方向 seam：proto 落地后由 Gate0CClient 实现为 blob_fetch 帧的薄映射
 * （每分块一个 offset 请求，控制帧在分块间抢占，与上传同一调度纪律）。
 */
internal interface BlobFetchChannel {
    /**
     * 从 offset 请求下一分块（≤ maxBytes）；源耗尽返回 null。
     * 源未知/越权/offset 不可续抛 BlobTransferWireException；载体故障抛其他异常。
     */
    suspend fun chunk(source: BlobFetchSource, offset: Long, maxBytes: Int): ByteArray?
}

/** 一次获取的终态。 */
internal sealed interface BlobFetchOutcome {
    /** 内容已在本地缓存并通过全部可核验声明。 */
    data class Ready(val file: File, val totalBytes: Long) : BlobFetchOutcome

    /** 载体层面中断：.part 现场保留，重连后同一调用续传。 */
    data class Retryable(val detail: String) : BlobFetchOutcome

    /** 传输级失败（越权/声明不符/通道违约）：暂存已清，重试从头开始。 */
    data class Failed(val detail: String) : BlobFetchOutcome
}

/**
 * ADR-005 客户端获取管线：按 offset 续传分块到 `blob-<key>.part`，全部
 * 可核验声明通过（声明大小、声明摘要——附件引用 id 即摘要）后原子发布为
 * `blob-<key>.bin` 缓存文件。发布即已核验：命中只做大小交叉检查（投影
 * 声明 ≠ 缓存大小 = 截断/腐坏，删除重取）。失败语义镜像上传管线：传输级
 * 失败清暂存，载体中断保留 .part 现场。同 key 并发单飞（追加同一 .part
 * 会腐坏游标）。
 */
internal class BlobFetchPipeline(
    private val cacheDir: File,
    private val channel: BlobFetchChannel,
    private val maxBlobBytes: Long = BlobUploadDeclaration.MAX_BLOB_BYTES,
    private val cacheBudgetBytes: Long = 256 * 1_048_576L,
    private val chunkBytes: Int = BlobUploadDeclaration.BLOB_CHUNK_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lockGuard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * 取回一个 blob。cacheKey 决定缓存身份（附件用 [cacheKeyForAttachment]，
     * 产物用 [cacheKeyForArtifact]）；sha256Hex/expectedBytes 是投影声明的
     * 可核验事实，缺失即少核验一项，绝不编造。
     */
    suspend fun fetch(
        cacheKey: String,
        source: BlobFetchSource,
        sha256Hex: String? = null,
        expectedBytes: Long? = null,
    ): BlobFetchOutcome {
        require(cacheKey.matches(CACHE_KEY_PATTERN)) { "Invalid blob cache key" }
        require(sha256Hex == null || sha256Hex.matches(SHA256_PATTERN)) { "Invalid blob sha256" }
        require(expectedBytes == null || expectedBytes > 0) { "Invalid blob size" }
        if (expectedBytes != null && expectedBytes > maxBlobBytes) {
            return BlobFetchOutcome.Failed("内容超过 ${maxBlobBytes / 1_048_576} MiB 上限")
        }
        val lock = lockGuard.withLock { locks.getOrPut(cacheKey) { Mutex() } }
        return lock.withLock { drive(cacheKey, source, sha256Hex, expectedBytes) }
    }

    private suspend fun drive(
        cacheKey: String,
        source: BlobFetchSource,
        sha256Hex: String?,
        expectedBytes: Long?,
    ): BlobFetchOutcome {
        cacheDir.mkdirs()
        val finalFile = File(cacheDir, "blob-$cacheKey.bin")
        val partFile = File(cacheDir, "blob-$cacheKey.part")
        if (finalFile.isFile) {
            val length = finalFile.length()
            if (expectedBytes == null || length == expectedBytes) {
                finalFile.setLastModified(now())
                return BlobFetchOutcome.Ready(finalFile, length)
            }
            finalFile.delete()
        }
        var cursor = partFile.length().takeIf { partFile.isFile } ?: 0L
        if (expectedBytes != null && cursor > expectedBytes) {
            partFile.delete()
            cursor = 0L
        }
        FileOutputStream(partFile, true).use { output ->
            while (true) {
                val chunk = try {
                    channel.chunk(source, cursor, chunkBytes)
                } catch (error: BlobTransferWireException) {
                    partFile.delete()
                    return BlobFetchOutcome.Failed("Host 拒绝了获取：${error.message}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return BlobFetchOutcome.Retryable("连接中断，可在恢复后续传")
                }
                if (chunk == null) break
                if (chunk.isEmpty() || chunk.size > chunkBytes) {
                    partFile.delete()
                    return BlobFetchOutcome.Failed("获取通道违反分块契约")
                }
                if (cursor + chunk.size > maxBlobBytes ||
                    (expectedBytes != null && cursor + chunk.size > expectedBytes)
                ) {
                    partFile.delete()
                    return BlobFetchOutcome.Failed("内容超出声明大小")
                }
                try {
                    output.write(chunk)
                    output.flush()
                } catch (error: Exception) {
                    return BlobFetchOutcome.Retryable("写入缓存失败：${error.message ?: error.javaClass.simpleName}")
                }
                cursor += chunk.size
            }
        }
        if (cursor == 0L) {
            partFile.delete()
            return BlobFetchOutcome.Failed("源内容为空")
        }
        if (expectedBytes != null && cursor != expectedBytes) {
            partFile.delete()
            return BlobFetchOutcome.Failed("内容与声明大小不符")
        }
        if (sha256Hex != null && digest(partFile) != sha256Hex) {
            partFile.delete()
            return BlobFetchOutcome.Failed("内容摘要与声明不符")
        }
        if (!publish(partFile, finalFile)) {
            return BlobFetchOutcome.Retryable("发布到缓存失败")
        }
        finalFile.setLastModified(now())
        sweep()
        return BlobFetchOutcome.Ready(finalFile, cursor)
    }

    private fun publish(partFile: File, finalFile: File): Boolean = try {
        java.nio.file.Files.move(
            partFile.toPath(),
            finalFile.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (_: Exception) {
        finalFile.delete()
        partFile.renameTo(finalFile).also { renamed ->
            if (!renamed) partFile.delete()
        }
    }

    /** LRU：超预算时按最后访问淘汰最旧的已发布文件；.part 是续传现场，不清扫。 */
    private fun sweep() {
        val finals = cacheDir
            .listFiles { file -> file.name.startsWith("blob-") && file.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = finals.sumOf { it.length() }
        for (file in finals) {
            if (total <= cacheBudgetBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val CACHE_KEY_PATTERN = Regex("[0-9a-f]{16,64}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private const val COPY_BUFFER_BYTES = 65_536

        /** 附件引用即摘要（ImageAttachmentRef.attachmentId = "sha256:<hex>"）：缓存键取 hex 部。 */
        fun cacheKeyForAttachment(attachmentId: String): String {
            require(attachmentId.startsWith("sha256:")) { "Invalid attachment id" }
            val hex = attachmentId.removePrefix("sha256:")
            require(hex.matches(SHA256_PATTERN)) { "Invalid attachment id" }
            return hex
        }

        /** 产物 id 是 Host 内部字符串：缓存键取其 sha256。 */
        fun cacheKeyForArtifact(artifactId: String): String {
            require(artifactId.isNotBlank() && artifactId.encodeToByteArray().size <= 512) { "Invalid artifact id" }
            return MessageDigest.getInstance("SHA-256")
                .digest(artifactId.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
