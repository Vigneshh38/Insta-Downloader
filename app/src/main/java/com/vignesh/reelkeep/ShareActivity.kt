package com.vignesh.reelkeep

import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens when you pick "Save with ReelKeep" in the share sheet. */
class ShareActivity : AppCompatActivity() {
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var loginBtn: MaterialButton
    private lateinit var grid: RecyclerView
    private lateinit var actions: LinearLayout
    private lateinit var selectAllBtn: MaterialButton
    private lateinit var downloadBtn: MaterialButton
    private lateinit var userAgent: String

    private var pendingCode: String? = null   // post waiting for you to log in
    private val adapter = MediaAdapter { updateButtons() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        loginBtn = findViewById(R.id.loginBtn)
        grid = findViewById(R.id.grid)
        actions = findViewById(R.id.actions)
        selectAllBtn = findViewById(R.id.selectAllBtn)
        downloadBtn = findViewById(R.id.downloadBtn)
        userAgent = WebSettings.getDefaultUserAgent(this)

        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter

        loginBtn.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        selectAllBtn.setOnClickListener {
            adapter.setAll(adapter.selected.size != adapter.items.size)
        }
        downloadBtn.setOnClickListener {
            val chosen = adapter.selectedItems()
            chosen.forEach { Downloader.enqueue(this, it, userAgent) }
            Toast.makeText(this, "Downloading ${chosen.size} file(s)…", Toast.LENGTH_SHORT).show()
            finish()
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        val code = InstagramApi.extractShortcode(text)
        if (code == null) {
            showMessage("This doesn't look like an Instagram post or reel link.")
            return
        }
        if (!InstagramApi.isLoggedIn()) {
            pendingCode = code
            showLogin()
        } else {
            load(code)
        }
    }

    override fun onResume() {
        super.onResume()
        // Came back from the login screen? Continue with the post.
        val code = pendingCode
        if (code != null && InstagramApi.isLoggedIn()) {
            pendingCode = null
            load(code)
        }
    }

    private fun load(code: String) {
        progress.isVisible = true
        loginBtn.isVisible = false
        status.text = "Finding the full-size file…"
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { InstagramApi.fetch(code, userAgent) }
                when {
                    items.isEmpty() -> showMessage("No photo or video found in this post.")
                    items.size == 1 -> {
                        val one = items[0]
                        Downloader.enqueue(this@ShareActivity, one, userAgent)
                        val kind = if (one.isVideo) "video" else "photo"
                        Toast.makeText(
                            this@ShareActivity,
                            "Downloading $kind (${one.width}×${one.height})",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                    else -> showPicker(items)
                }
            } catch (e: LoginRequiredException) {
                pendingCode = code
                showLogin()
            } catch (e: Exception) {
                showMessage(e.message ?: "Something went wrong. Check your internet and try again.")
            }
        }
    }

    private fun showPicker(items: List<MediaItem>) {
        progress.isVisible = false
        status.text = "This post has ${items.size} items. Tap to pick which ones to save."
        actions.isVisible = true
        adapter.submit(items)
    }

    private fun updateButtons() {
        val n = adapter.selected.size
        downloadBtn.text = "Download ($n)"
        downloadBtn.isEnabled = n > 0
        selectAllBtn.text = if (n == adapter.items.size) "Select none" else "Select all"
    }

    private fun showLogin() {
        progress.isVisible = false
        status.text = "You need to log in to Instagram inside ReelKeep once. After that, sharing works straight away."
        loginBtn.isVisible = true
    }

    private fun showMessage(msg: String) {
        progress.isVisible = false
        status.text = msg
    }
}
