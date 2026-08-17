package dev.dshremote.gate0c.transport

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** ADR-005 上传声明：与 Host blob-transfer 词汇一一对应。 */
internal data class BlobUploadDeclaration(
    val transferId: String,
    val sha256Hex: String,
    val totalBytes: Long,
    val mediaType: String?,
    val displayName: String?,
    val createdAtMs: Long,
) {
    init {
        require(transferId.matches(TRANSFER_ID_PATTERN)) { "Invalid blob transfer id" }
        require(sha256Hex.matches(SHA256_PATTERN)) { "Invalid blob sha256" }
        require(totalBytes in 1..MAX_BLOB_BYTES) { "Blob size out of bounds" }
    }

    companion object {
        /** 与 Host BLOB_CHUNK_BYTES 一致：Noise 明文上限减信封余量。 */
        const val BLOB_CHUNK_BYTES = 49_152

        /** 与 Host maxBlobBytes 默认值一致；Host 部署更严时以其拒绝为准（如实呈现）。 */
        const val MAX_BLOB_BYTES = 104_857_600L
        val TRANSFER_ID_PATTERN = Regex("[0-9a-f]{16,64}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** 上传声明的持久化 seam（Android 侧由加密 BlobUploadStore 实现；JVM 测试用内存实现）。 */
internal interface BlobUploadJournal {
    fun load(): BlobUploadDeclaration?
    fun save(declaration: BlobUploadDeclaration)
    fun clear()
}

/** 分块通道 seam：proto 落地后由 Gate0CClient 实现为 begin/chunk/control 帧的薄映射。 */
internal interface BlobUploadChannel {
    /** 打开或续传；返回 Host 持久游标。声明冲突抛 BlobTransferWireException。 */
    suspend fun begin(declaration: BlobUploadDeclaration): Long

    /** 重连后的续传查询；传输未知返回 null。 */
    suspend fun status(transferId: String): Long?

    /** 追加一个连续分块；offset 与 Host 游标错位抛 BlobUploadOffsetException。 */
    suspend fun chunk(transferId: String, offset: Long, data: ByteArray): Long

    /** 校验覆盖与摘要后经 owner 提交；返回 blob 引用。 */
    suspend fun complete(transferId: String): String

    /** 丢弃未完成传输；未知 id 为 no-op。 */
    suspend fun abort(transferId: String)
}

/** 传输级失败（Host 九大失败码的客户端面）；载体故障不走此类型。 */
internal class BlobTransferWireException(val code: String, detail: String) : Exception(detail)

/** Host 游标与本地不一致：携带权威续传点。 */
internal class BlobUploadOffsetException(val resumeOffset: Long) : Exception("Host cursor moved")

/** 一次上传的终态。 */
internal sealed interface BlobUploadOutcome {
    /** 提交成功，拿到 owner 侧 blob 引用（图片即 attachmentId）。 */
    data class Success(val blobId: String) : BlobUploadOutcome

    /** 载体层面中断（断连/进程死亡前的暂停点）：暂存完整，可 resumeStaged 续传。 */
    data class Retryable(val detail: String) : BlobUploadOutcome

    /** 传输级拒绝或本地暂存丢失：本地与 Host 暂存均已清理，需重新选择文件。 */
    data class Failed(val detail: String) : BlobUploadOutcome
}

/**
 * ADR-005 客户端上传管线：先把来源流式暂存到应用私有目录（同一遍算出
 * sha256 与总字节——声明先于上传，绝不虚报），再从暂存文件按 Host 游标
 * 续传分块。失败语义镜像 Host 组装器：传输级失败整传输重来；载体中断保留
 * 现场等待续传。本类不碰 Android API，可在 JVM 单测中完整演练。
 */
internal class BlobUploadPipeline(
    private val stagingDir: File,
    private val journal: BlobUploadJournal,
    private val channel: BlobUploadChannel,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 当前是否有可续传的暂存上传（UI 据此显示"继续上传"而非让重复选择）。 */
    fun stagedDeclaration(): BlobUploadDeclaration? = journal.load()

    /**
     * 暂存并上传一个新 blob。已有暂存上传时拒绝——先 resumeStaged 或 abandon，
     * 与 Host 的活跃传输预算一致（每 Host 单路上传）。
     */
    suspend fun stageAndUpload(
        openSource: () -> InputStream,
        displayName: String?,
        mediaType: String?,
    ): BlobUploadOutcome {
        require(journal.load() == null) { "Another blob upload is already staged" }
        stagingDir.mkdirs()
        // 无主暂存（上次成功清理前的残骸）先清扫，避免 cache 目录无限堆积。
        stagingDir.listFiles { file -> file.name.startsWith("upload-") }?.forEach { it.delete() }
        val transferId = ByteArray(16).also(secureRandom::nextBytes).toHex()
        val cacheFile = File(stagingDir, "upload-$transferId.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        try {
            openSource().use { input ->
                cacheFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > BlobUploadDeclaration.MAX_BLOB_BYTES) {
                            cacheFile.delete()
                            return BlobUploadOutcome.Failed("文件超过 ${BlobUploadDeclaration.MAX_BLOB_BYTES / 1_048_576} MiB 上限")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Exception) {
            cacheFile.delete()
            return BlobUploadOutcome.Failed("读取所选文件失败：${error.message ?: error.javaClass.simpleName}")
        }
        if (totalBytes == 0L) {
            cacheFile.delete()
            return BlobUploadOutcome.Failed("所选文件为空")
        }
        val declaration = BlobUploadDeclaration(
            transferId = transferId,
            sha256Hex = digest.digest().toHex(),
            totalBytes = totalBytes,
            mediaType = mediaType,
            displayName = displayName,
            createdAtMs = now(),
        )
        journal.save(declaration)
        return drive(declaration, cacheFile)
    }

    /** 重连/进程重启后的续传：声明与暂存文件俱在才能续；暂存丢失则如实失败并清理 Host 侧。 */
    suspend fun resumeStaged(): BlobUploadOutcome {
        val declaration = journal.load()
            ?: return BlobUploadOutcome.Failed("没有可续传的上传")
        val cacheFile = cacheFileOf(declaration)
        if (!cacheFile.isFile || cacheFile.length() != declaration.totalBytes) {
            runCatching { channel.abort(declaration.transferId) }
            journal.clear()
            return BlobUploadOutcome.Failed("本地暂存已丢失，请重新选择文件")
        }
        return drive(declaration, cacheFile)
    }

    /** 用户撤销：尽力中止 Host 侧传输并清理本地。 */
    suspend fun abandon() {
        val declaration = journal.load() ?: return
        runCatching { channel.abort(declaration.transferId) }
        journal.clear()
        cacheFileOf(declaration).delete()
    }

    private suspend fun drive(declaration: BlobUploadDeclaration, cacheFile: File): BlobUploadOutcome {
        var cursor = try {
            channel.begin(declaration)
        } catch (error: BlobTransferWireException) {
            return failTransfer(declaration, cacheFile, "Host 拒绝了上传声明：${error.message}")
        } catch (error: Exception) {
            return BlobUploadOutcome.Retryable("连接中断，可在恢复后续传")
        }
        if (cursor > declaration.totalBytes) {
            return failTransfer(declaration, cacheFile, "Host 游标越界（$cursor），传输已放弃")
        }
        var reconciliations = 0
        while (cursor < declaration.totalBytes) {
            val length = minOf(BlobUploadDeclaration.BLOB_CHUNK_BYTES.toLong(), declaration.totalBytes - cursor).toInt()
            val data = ByteArray(length)
            try {
                cacheFile.inputStream().use { input ->
                    skipFully(input, cursor)
                    readFully(input, data)
                }
            } catch (error: Exception) {
                return BlobUploadOutcome.Retryable("读取本地暂存失败：${error.message ?: error.javaClass.simpleName}")
            }
            cursor = try {
                val advanced = channel.chunk(declaration.transferId, cursor, data)
                if (advanced <= cursor) {
                    // 成功返回却零进度：与错位同等计数，防对异常 Host 死循环。
                    reconciliations += 1
                    if (reconciliations > MAX_RECONCILIATIONS) {
                        return BlobUploadOutcome.Retryable("Host 游标不再前进，暂停续传")
                    }
                }
                advanced
            } catch (error: BlobUploadOffsetException) {
                reconciliations += 1
                if (reconciliations > MAX_RECONCILIATIONS) {
                    return BlobUploadOutcome.Retryable("与 Host 游标多次不一致，暂停续传")
                }
                if (error.resumeOffset > cursor) {
                    return failTransfer(declaration, cacheFile, "Host 游标超过本地进度，传输状态不可信")
                }
                error.resumeOffset
            } catch (error: BlobTransferWireException) {
                return failTransfer(declaration, cacheFile, "Host 拒绝了分块：${error.message}")
            } catch (error: Exception) {
                return BlobUploadOutcome.Retryable("连接中断，可在恢复后续传")
            }
        }
        return try {
            val blobId = channel.complete(declaration.transferId)
            journal.clear()
            cacheFile.delete()
            BlobUploadOutcome.Success(blobId)
        } catch (error: BlobTransferWireException) {
            // Host 端收尾失败已删除其暂存（ADR-005：整传输重来）。
            journal.clear()
            cacheFile.delete()
            BlobUploadOutcome.Failed("Host 提交失败：${error.message ?: error.code}")
        } catch (error: Exception) {
            BlobUploadOutcome.Retryable("提交前连接中断，可在恢复后续传")
        }
    }

    private suspend fun failTransfer(
        declaration: BlobUploadDeclaration,
        cacheFile: File,
        detail: String,
    ): BlobUploadOutcome.Failed {
        runCatching { channel.abort(declaration.transferId) }
        journal.clear()
        cacheFile.delete()
        return BlobUploadOutcome.Failed(detail)
    }

    private fun cacheFileOf(declaration: BlobUploadDeclaration): File =
        File(stagingDir, "upload-${declaration.transferId}.bin")

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readFully(input: InputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            check(read >= 0) { "Staged blob shrank during upload" }
            offset += read
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val COPY_BUFFER_BYTES = 65_536
        const val MAX_RECONCILIATIONS = 3
    }
}
