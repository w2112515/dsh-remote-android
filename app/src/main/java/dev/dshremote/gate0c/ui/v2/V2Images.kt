package dev.dshremote.gate0c.ui.v2

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshremote.gate0c.transport.BlobFetchView
import dev.dshremote.gate0c.transport.ImageAttachmentProjection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 解码上限：缩略图与大图各自的像素预算，超限按 2 的幂降采样。 */
private const val THUMBNAIL_DIM = 384
private const val FULL_DIM = 2048

/** 缩略图结果：位图或失败原因（调用方如实降级并携带原因，绝不静默空白）。 */
internal data class UriBitmapResult(val bitmap: ImageBitmap?, val failure: String?)

/** 从内容 Uri 加载降采样位图（composer 缩略图）；失败给出原因，调用方如实降级。 */
@Composable
internal fun rememberUriBitmap(uri: String?, maxDim: Int = THUMBNAIL_DIM): UriBitmapResult {
    val context = LocalContext.current
    val result by produceState<UriBitmapResult>(initialValue = UriBitmapResult(null, null), uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val opened = resolver.openInputStream(android.net.Uri.parse(uri))
                if (opened == null) return@runCatching UriBitmapResult(null, "来源流无法打开")
                opened.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@runCatching UriBitmapResult(null, "无法解码为图片")
                }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = resolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                if (decoded == null) return@runCatching UriBitmapResult(null, "图片解码失败")
                UriBitmapResult(decoded.asImageBitmap(), null)
            }.getOrElse { error ->
                android.util.Log.w("V2Images", "composer thumbnail decode failed", error)
                UriBitmapResult(null, "${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }
    return result
}

/** 从已核验缓存文件加载降采样位图；失败为 null，调用方如实降级。 */
@Composable
internal fun rememberFileBitmap(file: File?, maxDim: Int = FULL_DIM): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (file == null || !file.isFile) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
            }.onFailure {
                android.util.Log.w("V2Images", "cached image decode failed", it)
            }.getOrNull()
        }
    }
    return bitmap
}

private sealed interface FetchedImageState {
    data object Loading : FetchedImageState
    data class Ready(val file: File) : FetchedImageState
    data class Unavailable(val detail: String, val retryable: Boolean) : FetchedImageState
}

/**
 * 时间线图片（S-blob）：引用经 blob 通道取回并核验（id 即摘要、声明大小）
 * 后才渲染。加载中、连接中断（可点击重试）、不可用三个非就绪态都如实标注，
 * 绝不渲染空白或占位假图。会话坐标缺失时不发起任何抓取。
 */
@Composable
internal fun V2FetchedImage(
    sessionId: String?,
    attachment: ImageAttachmentProjection,
    fetchImage: suspend (String, ImageAttachmentProjection) -> BlobFetchView,
    modifier: Modifier = Modifier,
) {
    val v2 = LocalV2.current
    var attempt by remember { mutableIntStateOf(0) }
    val fetched by produceState<FetchedImageState>(
        initialValue = FetchedImageState.Loading,
        sessionId, attachment.attachmentId, attempt,
    ) {
        if (sessionId == null) {
            value = FetchedImageState.Unavailable("会话坐标缺失，无法核验图片引用", retryable = false)
            return@produceState
        }
        value = when (val outcome = fetchImage(sessionId, attachment)) {
            is BlobFetchView.Ready -> FetchedImageState.Ready(outcome.file)
            is BlobFetchView.Retryable -> FetchedImageState.Unavailable(outcome.detail, retryable = true)
            is BlobFetchView.Failed -> FetchedImageState.Unavailable(outcome.detail, retryable = false)
        }
    }
    var fullOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    when (val current = fetched) {
        FetchedImageState.Loading -> Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .background(v2.card, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("正在经 blob 通道核验图片…", color = v2.tx3, fontSize = 10.sp)
        }
        is FetchedImageState.Unavailable -> Column(
            modifier = modifier
                .fillMaxWidth()
                .background(v2.card, shape)
                .then(
                    if (current.retryable) Modifier.clickable { attempt += 1 } else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                if (current.retryable) "图片未取回 · 点击重试" else "图片不可用",
                color = if (current.retryable) v2.amber else v2.red,
                fontSize = 10.sp,
            )
            Text(
                buildString {
                    append(current.detail)
                    attachment.name?.let { append(" · $it") }
                },
                color = v2.tx3,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
        }
        is FetchedImageState.Ready -> {
            val bitmap = rememberFileBitmap(current.file)
            if (bitmap == null) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .background(v2.card, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("已核验内容无法按图片解码", color = v2.red, fontSize = 10.sp)
                }
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.name ?: "消息图片",
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(shape)
                        .clickable { fullOpen = true }
                        .testTag("timeline-image"),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
    if (fullOpen && fetched is FetchedImageState.Ready) {
        val file = (fetched as FetchedImageState.Ready).file
        AlertDialog(
            onDismissRequest = { fullOpen = false },
            containerColor = v2.bg2,
            confirmButton = {
                TextButton(onClick = { fullOpen = false }) { Text("关闭", color = v2.blue) }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val bitmap = rememberFileBitmap(file, maxDim = FULL_DIM)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = attachment.name ?: "消息图片",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                    Text(
                        buildString {
                            append(attachment.mediaType)
                            append(" · ")
                            append(attachment.bytes)
                            append(" B")
                            attachment.width?.let { w -> attachment.height?.let { h -> append(" · ${w}×${h}") } }
                        },
                        color = v2.tx3,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )
    }
}
