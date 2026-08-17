package dev.dshremote.gate0c.transport

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.InputStream

/**
 * [BlobUriGateway] 的 ContentResolver 薄实现：DISPLAY_NAME/SIZE 查询失败
 * 或列缺失即对应字段为 null（声明大小缺失时由管线的流式上限兜底），
 * getType 失败视同 MIME 未知走魔数嗅探——绝不编造任何一项。
 */
internal class ContentResolverBlobUriGateway(private val resolver: ContentResolver) : BlobUriGateway {
    override fun open(uri: String): InputStream? =
        runCatching { resolver.openInputStream(uri.toUri()) }.getOrNull()

    override fun describe(uri: String): BlobUriDescription {
        val parsed = uri.toUri()
        val mediaType = runCatching { resolver.getType(parsed) }.getOrNull()
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val metadata = runCatching {
            resolver.query(parsed, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Pair(
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null,
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            }
        }.getOrNull()
        return BlobUriDescription(
            displayName = metadata?.first,
            mediaType = mediaType,
            declaredSizeBytes = metadata?.second,
        )
    }
}
