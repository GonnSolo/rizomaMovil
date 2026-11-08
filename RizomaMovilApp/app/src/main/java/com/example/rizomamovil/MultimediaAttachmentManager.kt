package com.example.rizomamovil

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================
 * SISTEMA DE TRANSFERENCIA MULTIMEDIA PARA RIZOMA MÓVIL (MODELO BRIAR)
 * ============================================================================
 * Adaptación directa de la arquitectura de adjuntos de Briar Android:
 * - org.briarproject.briar.api.messaging.AttachmentHeader -> AttachmentHeaderModel
 * - org.briarproject.briar.android.attachment.AttachmentItem -> AttachmentItemUi
 * - org.briarproject.briar.android.attachment.AttachmentCreatorImpl -> AttachmentCreatorManager
 * - org.briarproject.briar.android.attachment.AttachmentRetrieverImpl -> AttachmentRetrieverCache
 * - org.briarproject.briar.android.attachment.media.ImageCompressorImpl -> BriarImageCompressor
 * - org.briarproject.briar.android.attachment.ImageSizeCalculator -> BriarImageSizeCalculator
 *
 * Compatible tanto con el binario actual `libs/RizomaCore.aar` (`sendFileBytesAndroid`)
 * como con el nuevo método `sendMediaAttachmentAndroid` de `rizomacore`.
 */

enum class AttachmentState {
    LOADING,
    AVAILABLE,
    MISSING,
    ERROR
}

enum class MediaCategory {
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT,
    BINARY
}

/**
 * Equivalente en Kotlin a `org.briarproject.briar.api.messaging.AttachmentHeader`
 * y `org.briarproject.briar.android.attachment.AttachmentItem`.
 */
data class AttachmentItemUi(
    val transferId: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val localPath: String = "",
    val checksumSha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val state: AttachmentState = AttachmentState.LOADING,
    val chunksCompleted: Long = 0L,
    val totalChunks: Long = 1L,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalChunks > 0) {
            (chunksCompleted.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt()

    val category: MediaCategory
        get() = when {
            mimeType.startsWith("image/") -> MediaCategory.IMAGE
            mimeType.startsWith("audio/") -> MediaCategory.AUDIO
            mimeType.startsWith("video/") -> MediaCategory.VIDEO
            mimeType == "application/pdf" || mimeType.startsWith("text/") -> MediaCategory.DOCUMENT
            else -> MediaCategory.BINARY
        }
}

/**
 * Implementación fiel de `org.briarproject.briar.android.attachment.media.ImageCompressorImpl`
 * y `ImageSizeCalculator` de Briar:
 * 1. Escala cualquier imagen cuyas dimensiones superen MAX_ATTACHMENT_DIMENSION (1000 px).
 * 2. Al decodificar a Bitmap y recodificar a JPEG, elimina todos los metadatos EXIF
 *    (coordenadas GPS, modelo de cámara, fecha de captura) protegiendo el anonimato sobre Tor.
 * 3. Comprime iterativamente desde calidad 90 hasta 10 en pasos de 10 hasta que el payload
 *    sea <= MAX_COMPRESSED_IMAGE_BYTES (256 KB para streaming fluido en circuitos Tor).
 */
object BriarImageCompressor {
    const val MAX_ATTACHMENT_DIMENSION = 1000
    const val MAX_COMPRESSED_IMAGE_BYTES = 256 * 1024 // 256 KB (~16 chunks de 16 KB)

    data class CompressedImageResult(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val extension: String
    )

    fun compressImage(rawBytes: ByteArray, originalMime: String): CompressedImageResult {
        // 1. Leer solo dimensiones (inJustDecodeBounds = true) como en Briar ImageSizeCalculator
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)
        val srcW = boundsOptions.outWidth
        val srcH = boundsOptions.outHeight

        if (srcW <= 0 || srcH <= 0) {
            return CompressedImageResult(rawBytes, originalMime, 0, 0, "bin")
        }

        // 2. Calcular inSampleSize en potencias de 2 (patrón exacto de Briar ImageCompressorImpl)
        var inSampleSize = 1
        var halfW = srcW
        var halfH = srcH
        while (halfW / 2 >= MAX_ATTACHMENT_DIMENSION || halfH / 2 >= MAX_ATTACHMENT_DIMENSION) {
            halfW /= 2
            halfH /= 2
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            this.inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampledBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: return CompressedImageResult(rawBytes, originalMime, srcW, srcH, "jpg")

        // 3. Ajuste fino si aún supera 1000 px en algún eje
        val curW = sampledBitmap.width
        val curH = sampledBitmap.height
        val finalBitmap: Bitmap = if (curW > MAX_ATTACHMENT_DIMENSION || curH > MAX_ATTACHMENT_DIMENSION) {
            val scale = min(
                MAX_ATTACHMENT_DIMENSION.toFloat() / curW.toFloat(),
                MAX_ATTACHMENT_DIMENSION.toFloat() / curH.toFloat()
            )
            val targetW = max(1, (curW * scale).toInt())
            val targetH = max(1, (curH * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(sampledBitmap, targetW, targetH, true)
            if (scaled !== sampledBitmap) sampledBitmap.recycle()
            scaled
        } else {
            sampledBitmap
        }

        val finalW = finalBitmap.width
        val finalH = finalBitmap.height

        // 4. Bucle de compresión iterativa JPEG (calidad 90 -> 10 en pasos de 10, igual que Briar)
        val out = ByteArrayOutputStream()
        var quality = 90
        while (quality >= 10) {
            out.reset()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_COMPRESSED_IMAGE_BYTES) {
                break
            }
            quality -= 10
        }
        finalBitmap.recycle()

        return CompressedImageResult(
            bytes = out.toByteArray(),
            mimeType = "image/jpeg",
            width = finalW,
            height = finalH,
            extension = "jpg"
        )
    }
}

/**
 * Caché en memoria inspirada en `org.briarproject.briar.android.attachment.AttachmentRetrieverImpl`.
 * Evita decodificar Bitmaps repetidamente durante la recomposición de Jetpack Compose.
 */
object AttachmentRetrieverCache {
    private val bitmapCache = object : LruCache<String, ImageBitmap>(20 * 1024) { // 20 MB
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    fun getOrLoadBitmap(localPath: String, maxPreviewPx: Int = 720): ImageBitmap? {
        if (localPath.isBlank()) return null
        synchronized(bitmapCache) {
            bitmapCache.get(localPath)?.let { return it }
        }

        val file = File(localPath)
        if (!file.exists() || !file.canRead()) return null

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localPath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxPreviewPx * 2 || bounds.outHeight / sample > maxPreviewPx * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(localPath, opts) ?: return null
            val imageBitmap = bmp.asImageBitmap()
            synchronized(bitmapCache) {
                bitmapCache.put(localPath, imageBitmap)
            }
            imageBitmap
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Gestor de creación, preparación (staging) y recuperación de archivos multimedia,
 * basado en `AttachmentCreatorImpl` y `AttachmentCreationTask` de Briar.
 */
object MultimediaAttachmentHelper {
    private const val CHUNK_SIZE_BYTES = 16 * 1024L

    data class StagedAttachment(
        val item: AttachmentItemUi,
        val payloadBytes: ByteArray
    )

    fun resolveFileName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return sanitizeFilename(name ?: "adjunto_${System.currentTimeMillis()}")
    }

    fun resolveMimeType(context: Context, uri: Uri, filename: String): String {
        context.contentResolver.getType(uri)?.let { if (it.isNotBlank()) return it }
        return resolveMimeTypeFromFilename(filename)
    }

    fun resolveMimeTypeFromFilename(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    fun sanitizeFilename(raw: String): String {
        val base = raw.replace("\\", "/").substringAfterLast('/')
            .replace("..", "")
            .trim()
        return if (base.isEmpty() || base == "." || base == "/") {
            "adjunto_${System.currentTimeMillis()}.bin"
        } else {
            base
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Prepara un adjunto desde un `Uri` de Android SAF (`ActivityResultContracts.GetContent`),
     * aplicando la compresión y limpieza EXIF de Briar si es imagen, guardando una copia local
     * en `filesDir/downloads/sent/` (como `MessagingManager.addLocalAttachment` en Briar) y
     * devolviendo el `AttachmentItemUi` listo para renderizar en la conversación.
     */
    fun stageOutgoingUri(context: Context, uri: Uri): StagedAttachment? {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        if (rawBytes.isEmpty()) return null

        var cleanName = resolveFileName(context, uri)
        var mimeType = resolveMimeType(context, uri, cleanName)
        var finalBytes = rawBytes
        var width = 0
        var height = 0
        var durationMs = 0L

        if (mimeType.startsWith("image/") && mimeType != "image/gif") {
            val compressed = BriarImageCompressor.compressImage(rawBytes, mimeType)
            finalBytes = compressed.bytes
            mimeType = compressed.mimeType
            width = compressed.width
            height = compressed.height
            if (!cleanName.lowercase().endsWith(".jpg") && !cleanName.lowercase().endsWith(".jpeg")) {
                val base = cleanName.substringBeforeLast('.', cleanName)
                cleanName = "$base.jpg"
            }
        } else if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
            durationMs = extractMediaDurationMs(context, uri)
        }

        val checksum = sha256Hex(finalBytes)
        val transferId = checksum.take(16)
        val sentDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "loadingbay_in/sent").apply { mkdirs() }
        val localFile = File(sentDir, "${transferId}_$cleanName")
        FileOutputStream(localFile).use { it.write(finalBytes) }

        val totalChunks = ((finalBytes.size.toLong() + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES).coerceAtLeast(1L)

        val item = AttachmentItemUi(
            transferId = transferId,
            filename = cleanName,
            mimeType = mimeType,
            fileSize = finalBytes.size.toLong(),
            localPath = localFile.absolutePath,
            checksumSha256 = checksum,
            width = width,
            height = height,
            durationMs = durationMs,
            state = AttachmentState.AVAILABLE,
            chunksCompleted = totalChunks,
            totalChunks = totalChunks
        )
        return StagedAttachment(item = item, payloadBytes = finalBytes)
    }

    /**
     * Construye un `AttachmentItemUi` inicial en estado `LOADING` al recibir `onFileOffer`.
     */
    fun createIncomingOfferItem(filename: String, fileSize: Long): AttachmentItemUi {
        val cleanName = sanitizeFilename(filename)
        val mimeType = resolveMimeTypeFromFilename(cleanName)
        val totalChunks = ((fileSize + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES).coerceAtLeast(1L)
        return AttachmentItemUi(
            transferId = cleanName,
            filename = cleanName,
            mimeType = mimeType,
            fileSize = fileSize,
            state = AttachmentState.LOADING,
            chunksCompleted = 0L,
            totalChunks = totalChunks
        )
    }

    /**
     * Completa el `AttachmentItemUi` cuando `onFileReceived` entrega la ruta final verificada por SHA-256.
     */
    fun finalizeReceivedItem(filename: String, localPath: String): AttachmentItemUi {
        val cleanName = sanitizeFilename(filename)
        val file = File(localPath)
        val size = if (file.exists()) file.length() else 0L
        val mimeType = resolveMimeTypeFromFilename(cleanName)
        var width = 0
        var height = 0
        if (mimeType.startsWith("image/") && file.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localPath, opts)
            width = opts.outWidth
            height = opts.outHeight
        }
        val totalChunks = ((size + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES).coerceAtLeast(1L)
        return AttachmentItemUi(
            transferId = cleanName,
            filename = cleanName,
            mimeType = mimeType,
            fileSize = size,
            localPath = localPath,
            width = width,
            height = height,
            state = if (file.exists()) AttachmentState.AVAILABLE else AttachmentState.MISSING,
            chunksCompleted = totalChunks,
            totalChunks = totalChunks
        )
    }

    private fun extractMediaDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.2f MB", mb)
    }

    fun openAttachmentExternally(context: Context, item: AttachmentItemUi) {
        if (item.localPath.isBlank()) return
        val file = File(item.localPath)
        if (!file.exists()) {
            Toast.makeText(context, "Archivo no encontrado en almacenamiento local", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = try {
                FileProvider.getUriForFile(context, authority, file)
            } catch (_: Throwable) {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir ${item.filename}"))
        } catch (_: Throwable) {
            Toast.makeText(context, "Archivo guardado en: ${item.localPath}", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * ============================================================================
 * COMPONENTES UI JETPACK COMPOSE (ESTILO TERMINAL RIZOMA + MODELO BRIAR)
 * ============================================================================
 */

@Composable
fun BriarAttachmentStagingBar(
    stagedItem: AttachmentItemUi,
    onCancel: () -> Unit,
    onConfirmSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val terminalGreen = Color(0xFF00FF66)
    val darkSurface = Color(0xFF101814)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(darkSurface, RoundedCornerShape(8.dp))
            .border(1.dp, terminalGreen.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (stagedItem.category == MediaCategory.IMAGE && stagedItem.localPath.isNotBlank()) {
            val previewBmp = remember(stagedItem.localPath) {
                AttachmentRetrieverCache.getOrLoadBitmap(stagedItem.localPath, maxPreviewPx = 200)
            }
            if (previewBmp != null) {
                Image(
                    bitmap = previewBmp,
                    contentDescription = stagedItem.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, terminalGreen.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "LISTO PARA ENVIAR (${stagedItem.mimeType})",
                color = terminalGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stagedItem.filename,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val dims = if (stagedItem.width > 0 && stagedItem.height > 0) {
                " • ${stagedItem.width}x${stagedItem.height}px (EXIF limpio)"
            } else ""
            Text(
                text = "${MultimediaAttachmentHelper.formatBytes(stagedItem.fileSize)} • ${stagedItem.totalChunks} chunks$dims",
                color = Color(0xFF9E9E9E),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        TextButton(onClick = onCancel) {
            Text("✕", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = onConfirmSend,
            colors = ButtonDefaults.buttonColors(containerColor = terminalGreen, contentColor = Color.Black),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("ENVIAR", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BriarMultimediaBubbleCard(
    item: AttachmentItemUi,
    accentColor: Color = Color(0xFF00FF66),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardBg = Color(0xFF0B110E)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .clickable(enabled = item.state == AttachmentState.AVAILABLE && item.localPath.isNotBlank()) {
                MultimediaAttachmentHelper.openAttachmentExternally(context, item)
            }
            .padding(10.dp)
    ) {
        when (item.state) {
            AttachmentState.LOADING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⏳ TRANSFIRIENDO MULTIMEDIA",
                        color = accentColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${item.progressPercent}% (${item.chunksCompleted}/${item.totalChunks})",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Barra de progreso universal compatible con cualquier versión de Compose Material3
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1F2D24))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(item.progressFraction)
                            .fillMaxHeight()
                            .background(accentColor)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${item.filename} (${MultimediaAttachmentHelper.formatBytes(item.fileSize)})",
                    color = Color(0xFFB0BEC5),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            AttachmentState.AVAILABLE -> {
                if (item.category == MediaCategory.IMAGE && item.localPath.isNotBlank()) {
                    val bitmap = remember(item.localPath) {
                        AttachmentRetrieverCache.getOrLoadBitmap(item.localPath)
                    }
                    if (bitmap != null) {
                        val aspect = if (item.width > 0 && item.height > 0) {
                            (item.width.toFloat() / item.height.toFloat()).coerceIn(0.5f, 2.2f)
                        } else {
                            (bitmap.width.toFloat() / max(1, bitmap.height).toFloat()).coerceIn(0.5f, 2.2f)
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = item.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspect)
                                .heightIn(min = 120.dp, max = 260.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                val iconBadge = when (item.category) {
                    MediaCategory.IMAGE -> "🖼 IMAGEN"
                    MediaCategory.AUDIO -> "🎵 AUDIO"
                    MediaCategory.VIDEO -> "🎬 VIDEO"
                    MediaCategory.DOCUMENT -> "📄 DOCUMENTO"
                    MediaCategory.BINARY -> "📦 ARCHIVO"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$iconBadge • ${item.mimeType}",
                        color = accentColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "SHA-256 ✓",
                        color = Color(0xFF69F0AE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.filename,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${MultimediaAttachmentHelper.formatBytes(item.fileSize)} • Toca para abrir",
                    color = Color(0xFF90A4AE),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            AttachmentState.ERROR, AttachmentState.MISSING -> {
                Text(
                    text = "⚠ ERROR EN ADJUNTO: ${item.filename}",
                    color = Color(0xFFFF5252),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (!item.errorMessage.isNullOrBlank()) {
                    Text(
                        text = item.errorMessage,
                        color = Color(0xFFFF8A80),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}