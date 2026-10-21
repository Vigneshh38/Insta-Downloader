package com.vignesh.reelkeep

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

/** Home screen: log in / log out, and a box to paste a link by hand. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var loginBtn: MaterialButton
    private lateinit var logoutBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        loginBtn = findViewById(R.id.loginBtn)
        logoutBtn = findViewById(R.id.logoutBtn)
        val linkInput = findViewById<EditText>(R.id.linkInput)

        loginBtn.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        logoutBtn.setOnClickListener {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                refresh()
                Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.goBtn).setOnClickListener {
            val text = linkInput.text.toString().trim()
            if (!InstagramApi.looksLikeInstagram(text)) {
                Toast.makeText(this, "Paste an Instagram post or reel link", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ShareActivity::class.java).putExtra(Intent.EXTRA_TEXT, text))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val loggedIn = InstagramApi.isLoggedIn()
        status.text = if (loggedIn) {
            "✅ Logged in (optional). Private posts you follow will work too."
        } else {
            "✅ Ready. No login needed.\nIn Instagram: open a reel or post → Share → Save with ReelKeep."
        }
        loginBtn.isVisible = !loggedIn
        logoutBtn.isVisible = loggedIn
    }
}
