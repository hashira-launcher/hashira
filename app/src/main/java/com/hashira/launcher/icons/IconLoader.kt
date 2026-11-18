package com.hashira.launcher.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.hashira.launcher.data.AppEntry
import com.hashira.launcher.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Three tiers: memory (LRU by bytes) -> disk (cacheDir/icons, lossless WebP) -> render.
 * Resolution order when rendering: hand-picked icon -> pack icon -> system icon.
 * The key includes the update time of the app and the pack, so a change invalidates by itself.
 * Fallback icons (app not loaded yet) are never stored.
 */
class IconLoader(
    private val context: Context,
    private val apps: AppRepository,
    private val packs: IconPackManager
) {
    private val dir = File(context.cacheDir, "icons").apply { mkdirs() }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // Icon picker: its own cache (does not evict app icons) and decoding with bounded parallelism.
    private val packMemory = object : LruCache<String, ImageBitmap>(PACK_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoder = Dispatchers.Default.limitedParallelism(2)

    init {
        io.launch { trimDisk() }
    }

    private fun key(entry: AppEntry, packPkg: String?, override: String?, sizePx: Int): String =
        "${entry.key}|${apps.stamp(entry.packageName)}|$packPkg|${packPkg?.let { apps.stamp(it) }}|$override|$sizePx"

    /** Synchronous memory lookup: lets the icon be drawn in the first frame, no flicker. */
    fun peek(entry: AppEntry, packPkg: String?, override: String?, sizePx: Int): ImageBitmap? =
        memory.get(key(entry, packPkg, override, sizePx))

    suspend fun load(entry: AppEntry, packPkg: String?, override: String?, sizePx: Int): ImageBitmap {
        val key = key(entry, packPkg, override, sizePx)
        memory.get(key)?.let { return it }

        val file = fileFor(entry.packageName, key)
        val fromDisk = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }
        if (fromDisk != null) {
            memory.put(key, fromDisk)
            return fromDisk
        }

        return withContext(Dispatchers.Default) {
            val pack = packPkg?.let { packs.get(it) }
            val resolved: Drawable? = override?.let { pack?.load(it) }
                ?: pack?.drawableFor(entry.component)
                ?: apps.systemIcon(entry)
            val bitmap = (resolved ?: context.packageManager.defaultActivityIcon).toBitmap(sizePx, sizePx)
            val image = bitmap.asImageBitmap()
            if (resolved != null) {
                memory.put(key, image)
                io.launch { writeToDisk(file, bitmap) }
            }
            image
        }
    }

    private fun packKey(packPkg: String, name: String, sizePx: Int) =
        "$packPkg|${apps.stamp(packPkg)}|$name|$sizePx"

    fun peekPackIcon(packPkg: String, name: String, sizePx: Int): ImageBitmap? =
        packMemory.get(packKey(packPkg, name, sizePx))

    /** Icon from a pack (memory -> disk -> decode). Used by the picker, which shows hundreds at once. */
    suspend fun packIcon(packPkg: String, name: String, sizePx: Int): ImageBitmap? {
        val key = packKey(packPkg, name, sizePx)
        packMemory.get(key)?.let { return it }

        val file = fileFor(packPkg, "pk|$key")
        val fromDisk = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }
        if (fromDisk != null) {
            packMemory.put(key, fromDisk)
            return fromDisk
        }
        return withContext(decoder) {
            val drawable = packs.get(packPkg)?.load(name) ?: return@withContext null
            val bitmap = drawable.toBitmap(sizePx, sizePx)
            val image = bitmap.asImageBitmap()
            packMemory.put(key, image)
            io.launch { writeToDisk(file, bitmap) }
            image
        }
    }

    /** Warms the cache in the background (favorites first). Can be cancelled. */
    suspend fun preload(entries: List<AppEntry>, packPkg: String?, overrides: Map<String, String>, sizePx: Int) {
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            load(entry, packPkg, overrides[entry.key], sizePx)
        }
    }

    /** Deletes the icons of a package (memory keys start with "package/"). */
    fun evictPackage(pkg: String) {
        memory.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { memory.remove(it) }
        packMemory.snapshot().keys.filter { it.startsWith("$pkg|") }.forEach { packMemory.remove(it) }
        val prefix = safe(pkg) + "_"
        io.launch { dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() } }
    }

    fun clear() {
        memory.evictAll()
        packMemory.evictAll()
        io.launch { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(pkg: String, key: String) = File(dir, "${safe(pkg)}_${sha1(key)}.webp")

    private fun safe(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9._]"), "_")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun writeToDisk(file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
        }
    }

    /** If the disk cache exceeds the cap, deletes the oldest first. */
    private fun trimDisk() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_BYTES * 3 / 4) break
        }
    }

    private companion object {
        const val MEMORY_BYTES = 24 * 1024 * 1024
        const val PACK_MEMORY_BYTES = 12 * 1024 * 1024
        const val DISK_BYTES = 40L * 1024 * 1024
    }
}
