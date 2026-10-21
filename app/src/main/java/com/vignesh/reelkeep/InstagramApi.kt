package com.vignesh.reelkeep

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/** One photo or video inside a post. */
data class MediaItem(
    val index: Int,
    val isVideo: Boolean,
    val url: String,       // full-size file
    val thumbUrl: String,  // small preview picture
    val width: Int,
    val height: Int,
    val fileName: String,
)

class LoginRequiredException : Exception("Please log in to Instagram inside ReelKeep first.")

object InstagramApi {
    // Public id that instagram.com's own website sends with its requests.
    private const val WEB_APP_ID = "936619743392459"
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val client = OkHttpClient.Builder()
        .followRedirects(false) // a redirect here means "go log in"
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Matches /p/CODE, /reel/CODE, /reels/CODE, /tv/CODE, also /username/p/CODE
    private val LINK = Regex("""instagr(?:am\.com|\.am)/(?:[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)""")

    fun extractShortcode(text: String): String? = LINK.find(text)?.groupValues?.get(1)

    /** The short code in a link is the post's number written in base-64. Turn it back into the number. */
    fun shortcodeToMediaId(code: String): String {
        var id = BigInteger.ZERO
        val sixtyFour = BigInteger.valueOf(64)
        for (c in code.take(11)) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) break
            id = id.multiply(sixtyFour).add(BigInteger.valueOf(v.toLong()))
        }
        return id.toString()
    }

    fun cookies(): String = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""

    fun isLoggedIn(): Boolean = cookies().contains("sessionid=")

    /** Runs on a background thread. Returns every photo/video in the post at its biggest size. */
    fun fetch(shortcode: String, userAgent: String): List<MediaItem> {
        val cookie = cookies()
        if (!cookie.contains("sessionid=")) throw LoginRequiredException()
        val csrf = Regex("csrftoken=([^;]+)").find(cookie)?.groupValues?.get(1).orEmpty()
        val mediaId = shortcodeToMediaId(shortcode)

        val request = Request.Builder()
            .url("https://www.instagram.com/api/v1/media/$mediaId/info/")
            .header("User-Agent", userAgent)
            .header("Cookie", cookie)
            .header("X-IG-App-ID", WEB_APP_ID)
            .header("X-CSRFToken", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code in 300..399 || resp.code == 401 || body.contains("login_required")) {
                throw LoginRequiredException()
            }
            if (resp.code == 404) throw IOException("Post not found. It may be deleted, or from a private account you don't follow.")
            if (resp.code == 429) throw IOException("Instagram says too many requests. Wait a few minutes and try again.")
            if (!resp.isSuccessful) throw IOException("Instagram replied with error ${resp.code}. Try again later.")

            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw IOException("Instagram sent an unexpected reply. Try logging out and in again.")
            }
            val items = json.optJSONArray("items")
            if (items == null || items.length() == 0) throw IOException("No media found in this post.")
            return parsePost(items.getJSONObject(0), shortcode)
        }
    }

    private fun parsePost(post: JSONObject, code: String): List<MediaItem> {
        val user = post.optJSONObject("user")?.optString("username").orEmpty()
            .replace(Regex("[^A-Za-z0-9._]"), "")
        val prefix = if (user.isEmpty()) code else "${user}_$code"

        val carousel = post.optJSONArray("carousel_media")
        val nodes = if (carousel != null && carousel.length() > 0) {
            (0 until carousel.length()).map { carousel.getJSONObject(it) }
        } else {
            listOf(post)
        }
        return nodes.mapIndexedNotNull { i, node ->
            toItem(node, i, if (nodes.size > 1) "${prefix}_${i + 1}" else prefix)
        }
    }

    /** From a list of sizes, pick the one with the most pixels (width × height). */
    private fun biggest(arr: JSONArray?): JSONObject? {
        if (arr == null) return null
        var best: JSONObject? = null
        var bestPixels = -1L
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (c.optString("url").isEmpty()) continue
            val pixels = c.optLong("width") * c.optLong("height")
            if (pixels > bestPixels) {
                best = c
                bestPixels = pixels
            }
        }
        return best
    }

    private fun toItem(node: JSONObject, index: Int, name: String): MediaItem? {
        val image = biggest(node.optJSONObject("image_versions2")?.optJSONArray("candidates"))
        val video = biggest(node.optJSONArray("video_versions"))
        val thumb = image?.optString("url").orEmpty()
        return when {
            video != null -> MediaItem(
                index, true, video.getString("url"), thumb,
                video.optInt("width"), video.optInt("height"), "$name.mp4"
            )
            image != null -> {
                val url = image.getString("url")
                MediaItem(
                    index, false, url, thumb,
                    image.optInt("width"), image.optInt("height"), "$name.${imageExt(url)}"
                )
            }
            else -> null
        }
    }

    private fun imageExt(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".webp") -> "webp"
            path.endsWith(".heic") -> "heic"
            path.endsWith(".png") -> "png"
            else -> "jpg"
        }
    }
}
