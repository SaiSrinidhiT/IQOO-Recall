package com.hackathon.recall

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.hackathon.recall.ui.LocalContainer
import com.hackathon.recall.ui.RecallRoot
import com.hackathon.recall.ui.RecallTheme
import com.hackathon.recall.ui.ScreenCapture
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val renewalDocId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureFlag()
        handle(intent)
        val container = (application as RecallApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                RecallTheme { RecallRoot(renewalDocId, onRenewalHandled = { renewalDocId.value = null }) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        applySecureFlag()
        // Masked PDFs are temporary: once the user is back from the share sheet, delete them.
        (application as RecallApp).container.packBuilder.deleteSharedFiles()
    }

    private fun handle(intent: Intent?) {
        intent?.getLongExtra(EXTRA_RENEWAL_DOC_ID, -1)?.takeIf { it > 0 }?.let { renewalDocId.value = it }
    }

    /** FLAG_SECURE on vault screens (brief §8) unless the user enabled capture for a demo in Settings. */
    private fun applySecureFlag() {
        if (ScreenCapture.allowed(this)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        const val EXTRA_RENEWAL_DOC_ID = "renewal_doc_id"
    }
}
