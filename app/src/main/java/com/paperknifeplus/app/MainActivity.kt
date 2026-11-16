package com.paperknifeplus.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paperknifeplus.app.ui.EditorScreen
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme

class MainActivity : ComponentActivity() {
    private val editor: EditorViewModel by viewModels()
    private var incomingPdf by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a file to open; a recreated activity already has its document.
        if (savedInstanceState == null) incomingPdf = pdfFrom(intent)

        setContent {
            PaperKnifePlusTheme {
                EditorScreen(
                    vm = editor,
                    incomingPdf = incomingPdf,
                    onIncomingHandled = { incomingPdf = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pdfFrom(intent)?.let { incomingPdf = it }
    }

    private fun pdfFrom(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}
