package com.vignesh.reelkeep

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** Hands the file to Android's own download service, so it keeps going even after the app closes. */
object Downloader {
    fun enqueue(context: Context, item: MediaItem, userAgent: String) {
        val folder = if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val mime = when {
            item.isVideo -> "video/mp4"
            item.fileName.endsWith(".webp") -> "image/webp"
            item.fileName.endsWith(".heic") -> "image/heic"
            item.fileName.endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }
        val request = DownloadManager.Request(Uri.parse(item.url))
            .setTitle(item.fileName)
            .setDescription("${item.width}×${item.height}")
            .setMimeType(mime)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(folder, "ReelKeep/${item.fileName}")
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }
}
