package com.clipforge.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object Town {
    /** Name of the town the phone is in, or null. Needs ACCESS_COARSE_LOCATION already granted. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): String? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        var loc: Location? = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null && Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { lm.isProviderEnabled(it) } ?: return null
            loc = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { c ->
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { c.resume(it) }
                }
            }
        }
        val l = loc ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val a = Geocoder(ctx).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                a?.locality ?: a?.subAdminArea ?: a?.adminArea
            }.getOrNull()
        }
    }
}

object Share {
    private const val YT = "com.google.android.youtube"

    private fun sendIntent(uri: Uri, title: String, description: String) = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, description)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens the YouTube app's upload screen with this clip. Falls back to the share sheet. */
    fun toYouTube(ctx: Context, uri: Uri, title: String, description: String) {
        val i = sendIntent(uri, title, description).setPackage(YT)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            anywhere(ctx, uri, title, description)
        }
    }

    fun anywhere(ctx: Context, uri: Uri, title: String, description: String) {
        ctx.startActivity(Intent.createChooser(sendIntent(uri, title, description), "Share clip"))
    }

    fun watchAt(ctx: Context, videoId: String, sec: Double) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId&t=${sec.toInt()}s"))
        ctx.startActivity(i)
    }

    fun play(ctx: Context, uri: Uri) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
