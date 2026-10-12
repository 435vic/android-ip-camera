package com.github.digitallyrefined.androidipcamera.activities

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var service: StreamingService? = null
    private var bound = false
    private var hidden = false
    private var resumed = false
    private lateinit var backCallback: OnBackPressedCallback

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as StreamingService.LocalBinder).getService()
            bound = true
            service?.onStateChanged = { runOnUiThread { render() } }
            service?.onClientConnected = { runOnUiThread { render() } }
            service?.onClientDisconnected = { runOnUiThread { render() } }
            service?.startStreamingServer()
            if (resumed) service?.setPreviewSurface(binding.viewFinder.surfaceProvider)
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
            render()
        }
    }
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finishAndRemoveTask() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        hidden = savedInstanceState?.getBoolean("hidden") ?: false
        backCallback = object : OnBackPressedCallback(hidden) {
            override fun handleOnBackPressed() { setHidden(false) }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        ContextCompat.registerReceiver(this, closeReceiver,
            IntentFilter("com.github.digitallyrefined.androidipcamera.CLOSE_APP"), ContextCompat.RECEIVER_NOT_EXPORTED)
        binding.streamButton.setOnClickListener { service?.toggleStream() }
        binding.hidePreviewButton.setOnClickListener { setHidden(!hidden) }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.cameraButton.setOnClickListener { anchor ->
            val info = service?.streamingServerHelper?.deviceInfo() ?: return@setOnClickListener
            val cameras = info.getJSONArray("cameras")
            val selected = info.getJSONObject("settings").optString("cameraId")
            PopupMenu(this, anchor).apply {
                for (i in 0 until cameras.length()) {
                    val camera = cameras.getJSONObject(i)
                    menu.add(0, i, i, camera.getString("label")).apply {
                        isCheckable = true
                        isChecked = camera.getString("id") == selected
                    }
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener {
                    service?.control("camera", cameras.getJSONObject(it.itemId).getString("id"))
                    true
                }
                show()
            }
        }
        binding.zoomButton.setOnClickListener { anchor -> showZoom(anchor) }
        binding.micButton.setOnClickListener { anchor ->
            val current = service?.streamingServerHelper?.deviceInfo()?.getJSONObject("settings")?.optString("audioGain")?.toFloatOrNull() ?: 1f
            val values = listOf(0.5f, 1f, 1.5f, 2f, 3f)
            PopupMenu(this, anchor).apply {
                values.forEachIndexed { i, gain ->
                    menu.add(0, i, i, "Microphone gain ${gain}×").apply {
                        isCheckable = true
                        isChecked = current == gain
                    }
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener {
                    service?.control("audio_gain", values[it.itemId].toString())
                    true
                }
                show()
            }
        }
        if (permissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startAndBind()
        } else {
            ActivityCompat.requestPermissions(this, permissions() + Manifest.permission.RECORD_AUDIO, 10)
        }
        render()
    }

    private fun showZoom(anchor: View) {
        val info = service?.streamingServerHelper?.deviceInfo() ?: return
        val id = info.getJSONObject("settings").optString("cameraId")
        val cameras = info.getJSONArray("cameras")
        val camera = (0 until cameras.length()).map { cameras.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id } ?: return
        val zoom = camera.optJSONObject("zoom") ?: return
        val min = zoom.optDouble("min", 1.0).toFloat()
        val max = zoom.optDouble("max", 1.0).toFloat()
        val current = camera.optJSONObject("lensSettings")?.optString("zoom")?.toFloatOrNull() ?: min
        val values = (listOf(min, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f, max, current)
            .filter { it in min..max }).distinct().sorted()
        PopupMenu(this, anchor).apply {
            values.forEachIndexed { i, value ->
                menu.add(0, i, i, String.format(Locale.US, "%.1f×", value)).apply {
                    isCheckable = true
                    isChecked = kotlin.math.abs(current - value) < 0.05f
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { service?.control("zoom", values[it.itemId].toString()); true }
            show()
        }
    }

    private fun startAndBind() {
        val intent = Intent(this, StreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setHidden(value: Boolean) {
        hidden = value
        backCallback.isEnabled = value
        render()
        immersive()
    }

    private fun render() {
        val active = service?.streamEnabled == true
        val remote = service?.hasActiveClients() == true
        binding.controls.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        // Alpha leaves the PreviewView surface/session intact. Visibility is display-only:
        // it must not restart a remote stream, recording, or microphone.
        binding.viewFinder.alpha = if (!hidden && active && !remote) 1f else 0f
        binding.streamPreview.visibility = if (!hidden && active && remote) View.VISIBLE else View.INVISIBLE
        if (!active || !remote) binding.streamPreview.setImageDrawable(null)
        binding.streamButton.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_start)
        binding.streamButton.contentDescription = if (active) "Stop stream" else "Start stream"
        binding.hidePreviewButton.contentDescription = if (hidden) "Show preview" else "Hide preview"
        binding.streamButton.isEnabled = bound
        service?.onPreviewJpeg = if (resumed && !hidden) { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            runOnUiThread {
                if (resumed && !hidden && service?.streamEnabled == true) binding.streamPreview.setImageBitmap(bitmap)
            }
        } else null
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        service?.setPreviewSurface(binding.viewFinder.surfaceProvider)
        render()
        immersive()
    }
    override fun onPause() {
        resumed = false
        service?.onPreviewJpeg = null
        service?.setPreviewSurface(null)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("hidden", hidden)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (bound) {
            service?.onStateChanged = null
            service?.onClientConnected = null
            service?.onClientDisconnected = null
            service?.onPreviewJpeg = null
            unbindService(connection)
        }
        unregisterReceiver(closeReceiver)
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 10) return
        if (permissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startAndBind()
        } else AlertDialog.Builder(this).setTitle("Camera permission required")
            .setMessage("Allow camera and notification access in Android app settings.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }.setNegativeButton("Close") { _, _ -> finish() }.show()
    }
    private fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT <= 28 -> arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.CAMERA)
    }
}
