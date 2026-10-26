package com.galaxy.musicpro

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var currentTrackId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        webView.setBackgroundColor(Color.BLACK)
        webView.webChromeClient = WebChromeClient()

        // Intercept local art URLs to stream embedded album art directly from MediaStore
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.startsWith("https://local.art/")) {
                    return try {
                        val albumId = url.removePrefix("https://local.art/").toLong()
                        val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
                        val albumArtUri = ContentUris.withAppendedId(sArtworkUri, albumId)
                        val inputStream = contentResolver.openInputStream(albumArtUri)
                        WebResourceResponse("image/jpeg", "UTF-8", inputStream)
                    } catch (e: Exception) {
                        null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.addJavascriptInterface(MusicBridge(), "AndroidBridge")
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
        } else {
            loadAppUI()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadAppUI()
    }

    private fun loadAppUI() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun initAudioEffects(sessionId: Int) {
        try {
            equalizer?.release()
            bassBoost?.release()

            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            bassBoost = BassBoost(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    inner class MusicBridge {

        @JavascriptInterface
        fun scanAllMusic(): String {
            val jsonArray = JSONArray()
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            try {
                contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "Unknown Track"
                        val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                        val album = cursor.getString(albumCol) ?: "Unknown Album"
                        val albumId = cursor.getLong(albumIdCol)
                        val duration = cursor.getLong(durCol)
                        val path = cursor.getString(dataCol) ?: ""
                        val folder = try {
                            File(path).parentFile?.name ?: "Internal Storage"
                        } catch (e: Exception) {
                            "Music"
                        }

                        if (duration > 3000) { // Skip short notification audio
                            val obj = JSONObject()
                            obj.put("id", id)
                            obj.put("title", title)
                            obj.put("artist", if (artist == "<unknown>") "Unknown Artist" else artist)
                            obj.put("album", album)
                            obj.put("albumId", albumId)
                            obj.put("duration", duration / 1000)
                            obj.put("folder", folder)
                            obj.put("art", "https://local.art/$albumId")
                            jsonArray.put(obj)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return jsonArray.toString()
        }

        @JavascriptInterface
        fun playTrack(id: Long): Boolean {
            return try {
                currentTrackId = id
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(applicationContext, contentUri)
                    prepare()
                    start()
                    setOnCompletionListener {
                        runOnUiThread {
                            webView.evaluateJavascript("window.onNativeTrackEnded()", null)
                        }
                    }
                }
                mediaPlayer?.audioSessionId?.let { initAudioEffects(it) }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        @JavascriptInterface
        fun togglePlayPause(): Boolean {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    return false
                } else {
                    it.start()
                    return true
                }
            }
            return false
        }

        @JavascriptInterface
        fun seekTo(seconds: Int) {
            mediaPlayer?.seekTo(seconds * 1000)
        }

        @JavascriptInterface
        fun setSpeed(speed: Float) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mediaPlayer?.let {
                        val isPlaying = it.isPlaying
                        it.playbackParams = it.playbackParams.setSpeed(speed)
                        if (!isPlaying) it.pause()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun getCurrentPosition(): Int {
            return try {
                (mediaPlayer?.currentPosition ?: 0) / 1000
            } catch (e: Exception) {
                0
            }
        }

        @JavascriptInterface
        fun isPlaying(): Boolean {
            return try {
                mediaPlayer?.isPlaying ?: false
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun setEqBand(bandIndex: Int, levelPercent: Int) {
            try {
                equalizer?.let { eq ->
                    val numBands = eq.numberOfBands
                    if (bandIndex < numBands) {
                        val min = eq.bandLevelRange[0]
                        val max = eq.bandLevelRange[1]
                        val mapped = ((levelPercent + 15) / 30.0f * (max - min) + min).toInt().toShort()
                        eq.setBandLevel(bandIndex.toShort(), mapped)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setBassBoostLevel(strength: Int) {
            try {
                bassBoost?.setStrength(strength.toShort())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.handleBackPress()") { result ->
            if (result != "\"true\"" && result != "true") {
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        equalizer?.release()
        bassBoost?.release()
        mediaPlayer?.release()
    }
}
