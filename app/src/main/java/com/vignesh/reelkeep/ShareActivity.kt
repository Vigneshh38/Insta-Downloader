package com.vignesh.reelkeep

import android.content.Intent
import android.os.Bundle
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

/** Opens when you pick "Save with ReelKeep" in the share sheet. No login needed for public posts. */
class ShareActivity : AppCompatActivity() {
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var loginBtn: MaterialButton
    private lateinit var grid: RecyclerView
    private lateinit var actions: LinearLayout
    private lateinit var selectAllBtn: MaterialButton
    private lateinit var downloadBtn: MaterialButton

    private var sharedText = ""
    private var retryAfterLogin = false
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

        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter

        loginBtn.text = "Private account? Log in (optional)"
        loginBtn.setOnClickListener {
            retryAfterLogin = true
            startActivity(Intent(this, LoginActivity::class.java))
        }
        selectAllBtn.setOnClickListener {
            adapter.setAll(adapter.selected.size != adapter.items.size)
        }
        downloadBtn.setOnClickListener {
            val chosen = adapter.selectedItems()
            chosen.forEach { Downloader.enqueue(this, it, InstagramApi.UA) }
            Toast.makeText(this, "Downloading ${chosen.size} file(s)…", Toast.LENGTH_SHORT).show()
            finish()
        }

        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        if (!InstagramApi.looksLikeInstagram(sharedText)) {
            showMessage("This doesn't look like an Instagram post or reel link.")
            return
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // Came back from the optional login screen? Try again.
        if (retryAfterLogin && InstagramApi.isLoggedIn()) {
            retryAfterLogin = false
            load()
        }
    }

    private fun load() {
        progress.isVisible = true
        loginBtn.isVisible = false
        status.text = "Finding the full-size file…"
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val code = InstagramApi.resolveShortcode(sharedText)
                        ?: throw IllegalStateException("Couldn't read the post link.")
                    InstagramApi.fetch(code)
                }
                when {
                    items.isEmpty() -> showError("No photo or video found in this post.")
                    items.size == 1 -> {
                        val one = items[0]
                        Downloader.enqueue(this@ShareActivity, one, InstagramApi.UA)
                        val kind = if (one.isVideo) "video" else "photo"
                        val size = if (one.width > 0) " (${one.width}×${one.height})" else ""
                        Toast.makeText(this@ShareActivity, "Downloading $kind$size", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    else -> showPicker(items)
                }
            } catch (e: Exception) {
                showError(e.message ?: "Something went wrong. Check your internet and try again.")
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

    /** Error: also offer the optional login, since private posts need it. */
    private fun showError(msg: String) {
        progress.isVisible = false
        status.text = msg
        loginBtn.isVisible = !InstagramApi.isLoggedIn()
    }

    private fun showMessage(msg: String) {
        progress.isVisible = false
        status.text = msg
    }
}
