package com.gk.smartcompressor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.IntentCompat
import com.gk.smartcompressor.core.FileInfo
import com.gk.smartcompressor.core.SourceInfo
import com.gk.smartcompressor.ui.AppTheme
import com.gk.smartcompressor.ui.MainScreen

class MainActivity : ComponentActivity() {
    /** Files jo chuni gayi hain lekin abhi compress shuru nahi hua. */
    val staged = mutableStateListOf<Pair<Uri, SourceInfo>>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        handleShare(intent)
        setContent { AppTheme { MainScreen(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    fun addUris(uris: List<Uri>) {
        for (u in uris) {
            runCatching {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (staged.none { it.first == u }) runCatching { staged.add(u to FileInfo.read(this, u)) }
        }
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) addUris(uris)
    }
}
