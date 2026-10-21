package com.vignesh.reelkeep

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
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

object InstagramApi {
    /** Browser name we send, so Instagram treats us like a normal desktop browser. */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    // Public id that instagram.com's own website sends with its requests.
    private const val WEB_APP_ID = "936619743392459"
    private const val LSD = "AVqbxe3J_YA"

    // Ids of the "load one post" query used by instagram.com when you are logged out.
    // Instagram changes this now and then, so we try a few.
    private val DOC_IDS = listOf("8845758582119845", "10015901848480474")

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Keeps the small cookies Instagram gives a logged-out visitor (like csrftoken). */
    private object MemoryCookies : CookieJar {
        private val store = mutableMapOf<String, Cookie>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.name] = it }
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.values.filter { it.matches(url) }
        @Synchronized
        fun get(name: String): String? = store[name]?.value
    }

    private val guest = OkHttpClient.Builder()
        .cookieJar(MemoryCookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val loggedInClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Matches /p/CODE, /reel/CODE, /reels/CODE, /tv/CODE, also /username/p/CODE
    private val LINK =
        Regex("""instagr(?:am\.com|\.am)/(?:[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)""")
    private val SHARE_LINK = Regex("""https?://(?:www\.)?instagram\.com/share/[^\s]+""")

    fun extractShortcode(text: String): String? =
        LINK.find(text)?.groupValues?.get(1)

    fun looksLikeInstagram(text: String): Boolean =
        extractShortcode(text) != null || SHARE_LINK.containsMatchIn(text)

    /** Newer "instagram.com/share/..." links jump to the real post link. Follow the jump. */
    fun resolveShortcode(text: String): String? {
        extractShortcode(text)?.let { return it }
        val share = SHARE_LINK.find(text)?.value ?: return null
        return try {
            val req = Request.Builder().url(share).header("User-Agent", UA).build()
            guest.newCall(req).execute().use { extractShortcode(it.request.url.toString()) }
        } catch (e: IOException) {
            null
        }
    }

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

    /**
     * Runs on a background thread. Returns every photo/video in the post at its biggest size.
     * Tries, in order: (1) your login, only if you chose to log in, (2) logged-out website query,
     * (3) the post's embed page.
     */
    fun fetch(shortcode: String): List<MediaItem> {
        val problems = mutableListOf<String>()

        if (isLoggedIn()) {
            try {
                return fetchLoggedIn(shortcode)
            } catch (e: Exception) {
                problems += "login: ${e.message}"
            }
        }

        warmUp()
        for (doc in DOC_IDS) {
            try {
                val media = fetchGraphql(shortcode, doc)
                if (media != null) {
                    val items = parseGraph(media, shortcode)
                    if (items.isNotEmpty()) return items
                }
            } catch (e: Exception) {
                problems += "query: ${e.message}"
            }
        }

        try {
            val items = fetchEmbed(shortcode)
            if (items.isNotEmpty()) return items
        } catch (e: Exception) {
            problems += "embed: ${e.message}"
        }

        throw IOException(
            "Couldn't get this post. It may be from a private account, deleted, or Instagram " +
                "is limiting requests right now (try again in a few minutes)." +
                if (problems.isNotEmpty()) "\n\nDetails: ${problems.last()}" else ""
        )
    }

    // ---------- (1) optional logged-in path: best quality ----------

    private fun fetchLoggedIn(shortcode: String): List<MediaItem> {
        val cookie = cookies()
        val csrf = Regex("csrftoken=([^;]+)").find(cookie)?.groupValues?.get(1).orEmpty()
        val request = Request.Builder()
            .url("https://www.instagram.com/api/v1/media/${shortcodeToMediaId(shortcode)}/info/")
            .header("User-Agent", UA)
            .header("Cookie", cookie)
            .header("X-IG-App-ID", WEB_APP_ID)
            .header("X-CSRFToken", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .header("Accept", "application/json")
            .build()
        loggedInClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("error ${resp.code}")
            val items = JSONObject(body).optJSONArray("items")
            if (items == null || items.length() == 0) throw IOException("no items")
            return parseApiPost(items.getJSONObject(0), shortcode)
        }
    }

    private fun parseApiPost(post: JSONObject, code: String): List<MediaItem> {
        val prefix = namePrefix(post.optJSONObject("user")?.optString("username"), code)
        val carousel = post.optJSONArray("carousel_media")
        val nodes = if (carousel != null && carousel.length() > 0) {
            (0 until carousel.length()).map { carousel.getJSONObject(it) }
        } else listOf(post)
        return nodes.mapIndexedNotNull { i, node ->
            val name = if (nodes.size > 1) "${prefix}_${i + 1}" else prefix
            val image = biggest(node.optJSONObject("image_versions2")?.optJSONArray("candidates"),
                "url", "width", "height")
            val video = biggest(node.optJSONArray("video_versions"), "url", "width", "height")
            val thumb = image?.optString("url").orEmpty()
            when {
                video != null -> MediaItem(i, true, video.getString("url"), thumb,
                    video.optInt("width"), video.optInt("height"), "$name.mp4")
                image != null -> {
                    val url = image.getString("url")
                    MediaItem(i, false, url, thumb, image.optInt("width"), image.optInt("height"),
                        "$name.${imageExt(url)}")
                }
                else -> null
            }
        }
    }

    // ---------- (2) logged-out website query ----------

    /** Visit the home page once so Instagram gives us the normal visitor cookies. */
    private fun warmUp() {
        if (MemoryCookies.get("csrftoken") != null) return
        try {
            val req = Request.Builder().url("https://www.instagram.com/")
                .header("User-Agent", UA).header("Accept", "text/html").build()
            guest.newCall(req).execute().close()
        } catch (e: IOException) {
        }
    }

    private fun fetchGraphql(shortcode: String, docId: String): JSONObject? {
        val variables = JSONObject()
            .put("shortcode", shortcode)
            .put("fetch_tagged_user_count", JSONObject.NULL)
            .put("hoisted_comment_id", JSONObject.NULL)
            .put("hoisted_reply_id", JSONObject.NULL)
        val form = FormBody.Builder()
            .add("av", "0")
            .add("__d", "www")
            .add("__user", "0")
            .add("__a", "1")
            .add("__req", "3")
            .add("dpr", "3")
            .add("lsd", LSD)
            .add("fb_api_caller_class", "RelayModern")
            .add("fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery")
            .add("variables", variables.toString())
            .add("server_timestamps", "true")
            .add("doc_id", docId)
            .build()
        val req = Request.Builder()
            .url("https://www.instagram.com/graphql/query")
            .post(form)
            .header("User-Agent", UA)
            .header("X-IG-App-ID", WEB_APP_ID)
            .header("X-FB-LSD", LSD)
            .header("X-ASBD-ID", "129477")
            .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
            .header("X-CSRFToken", MemoryCookies.get("csrftoken").orEmpty())
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Accept", "*/*")
            .build()
        guest.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 429) throw IOException("too many requests (429)")
            if (!resp.isSuccessful) throw IOException("error ${resp.code}")
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw IOException("unexpected reply")
            }
            val data = json.optJSONObject("data") ?: return null
            return data.optJSONObject("xdt_shortcode_media") ?: data.optJSONObject("shortcode_media")
        }
    }

    /** Works for both the logged-out query and the embed page (same data shape). */
    private fun parseGraph(media: JSONObject, code: String): List<MediaItem> {
        val prefix = namePrefix(media.optJSONObject("owner")?.optString("username"), code)
        val edges = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        val nodes = if (edges != null && edges.length() > 0) {
            (0 until edges.length()).mapNotNull { edges.optJSONObject(it)?.optJSONObject("node") }
        } else listOf(media)

        return nodes.mapIndexedNotNull { i, node ->
            val name = if (nodes.size > 1) "${prefix}_${i + 1}" else prefix
            val dims = node.optJSONObject("dimensions")
            val w = dims?.optInt("width") ?: 0
            val h = dims?.optInt("height") ?: 0
            val bestImage = biggest(node.optJSONArray("display_resources"),
                "src", "config_width", "config_height")
            val imageUrl = bestImage?.optString("src")?.takeIf { it.isNotEmpty() }
                ?: node.optString("display_url")
            val videoUrl = node.optString("video_url")
            when {
                node.optBoolean("is_video") && videoUrl.isNotEmpty() ->
                    MediaItem(i, true, videoUrl, imageUrl, w, h, "$name.mp4")
                imageUrl.isNotEmpty() -> {
                    val iw = bestImage?.optInt("config_width")?.takeIf { it > 0 } ?: w
                    val ih = bestImage?.optInt("config_height")?.takeIf { it > 0 } ?: h
                    MediaItem(i, false, imageUrl, imageUrl, iw, ih, "$name.${imageExt(imageUrl)}")
                }
                else -> null
            }
        }
    }

    // ---------- (3) embed page fallback ----------

    private val CONTEXT_JSON = Regex(""""contextJSON":"((?:\\.|[^"\\])*)"""")
    private val EMBED_IMG = Regex("""class="EmbeddedMediaImage"[^>]*?src="([^"]+)"""")

    private fun fetchEmbed(shortcode: String): List<MediaItem> {
        val req = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/embed/captioned/")
            .header("User-Agent", UA)
            .header("Accept", "text/html")
            .build()
        val html = guest.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("error ${resp.code}")
            resp.body?.string().orEmpty()
        }

        // Full post data is sometimes tucked inside the embed page.
        CONTEXT_JSON.find(html)?.groupValues?.get(1)?.let { raw ->
            try {
                val text = JSONObject("{\"v\":\"$raw\"}").getString("v")
                val media = JSONObject(text).optJSONObject("gql_data")?.optJSONObject("shortcode_media")
                if (media != null) {
                    val items = parseGraph(media, shortcode)
                    if (items.isNotEmpty()) return items
                }
            } catch (e: JSONException) {
            }
        }

        // Last resort: the picture shown on the embed page.
        val img = EMBED_IMG.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: throw IOException("nothing found on embed page")
        return listOf(MediaItem(0, false, img, img, 0, 0, "$shortcode.${imageExt(img)}"))
    }

    // ---------- helpers ----------

    private fun namePrefix(username: String?, code: String): String {
        val user = username.orEmpty().replace(Regex("[^A-Za-z0-9._]"), "")
        return if (user.isEmpty()) code else "${user}_$code"
    }

    /** From a list of sizes, pick the one with the most pixels (width × height). */
    private fun biggest(arr: JSONArray?, urlKey: String, wKey: String, hKey: String): JSONObject? {
        if (arr == null) return null
        var best: JSONObject? = null
        var bestPixels = -1L
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (c.optString(urlKey).isEmpty()) continue
            val pixels = c.optLong(wKey) * c.optLong(hKey)
            if (pixels > bestPixels) {
                best = c
                bestPixels = pixels
            }
        }
        return best
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
