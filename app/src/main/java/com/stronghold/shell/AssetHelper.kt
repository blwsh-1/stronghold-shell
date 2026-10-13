package com.stronghold.shell

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把 assets/app_bundle.zip 解压到私有 files 目录。
 *
 * 版本标记机制（与原壳一致，这样前端换包逻辑通用）：
 *   - assets/bundle.sha256 里存一个「版本标记字符串」（不是真哈希）
 *   - 私有 files/bundle/.bundle_version 存已解压的标记
 *   - 两者不一致 → 删旧目录、重新解压
 */
object AssetHelper {

    private const val TAG = "StrongholdShell"
    private const val BUNDLE_NAME = "app_bundle.zip"
    private const val MARKER_ASSET = "bundle.sha256"

    fun bundleDir(context: Context): File {
        val dir = File(context.filesDir, "bundle")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun versionFile(context: Context): File =
        File(bundleDir(context), ".bundle_version")

    /** 读取 assets 里的版本标记（不是真哈希） */
    private fun assetMarker(context: Context): String {
        return try {
            context.assets.open(MARKER_ASSET).bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            ""
        }
    }

    fun ensureAssetsExtracted(context: Context) {
        try {
            val desired = assetMarker(context)
            val markerFile = versionFile(context)
            val current = if (markerFile.exists()) markerFile.readText().trim() else ""

            if (current == desired && desired.isNotEmpty()) {
                Log.i(TAG, "资源已是最新标记: $desired")
                return
            }

            Log.i(TAG, "需要解压资源: current='$current' desired='$desired'")
            extractBundle(context)
            markerFile.writeText(desired)
        } catch (e: Throwable) {
            Log.e(TAG, "资源解压失败", e)
            FileLogger.log("资源解压失败: ${e.message}")
        }
    }

    private fun extractBundle(context: Context) {
        val dir = bundleDir(context)
        // 清空旧内容
        dir.listFiles()?.forEach { it.deleteRecursively() }

        val hasZip = try {
            context.assets.open(BUNDLE_NAME).use { true }
        } catch (e: Exception) {
            false
        }

        if (!hasZip) {
            // 回退：直接拷贝 assets（对应原壳的 direct asset copy）
            Log.w(TAG, "app_bundle.zip 不存在，回退直接拷贝 assets")
            copyAssetDir(context, "", dir)
            return
        }

        Log.i(TAG, "解压 $BUNDLE_NAME → ${dir.absolutePath}")
        java.util.zip.ZipInputStream(context.assets.open(BUNDLE_NAME)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(dir, entry.name)
                // 防目录穿越
                if (!outFile.canonicalPath.startsWith(dir.canonicalPath)) {
                    entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.i(TAG, "解压完成")
    }

    /** 递归拷贝 assets 子目录（回退路径用） */
    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val list = context.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // 是文件
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { ins ->
                dest.outputStream().use { ins.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (name in list) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            copyAssetDir(context, childAsset, File(dest, name))
        }
    }
}