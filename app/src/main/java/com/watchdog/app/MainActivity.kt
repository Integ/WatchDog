package com.watchdog.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watchdog.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        private val REQUESTED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var binding: ActivityMainBinding
    private var rtspService: RtspService? = null
    private var isBound = false
    private var shouldStartService = false
    private var isStartRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RtspService.LocalBinder ?: return
            rtspService = binder.getService()
            isBound = true
            updateServerUi()
            rtspService?.attachPreview(binding.previewView.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rtspService = null
            isBound = false
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
            shouldStartService = true
            startServiceIfNeeded()
            bindServiceIfNeeded()
        } else {
            binding.txtServer.text = "Camera permission is required to start streaming."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            shouldStartService = true
            startServiceIfNeeded()
        } else {
            requestPermissionsLauncher.launch(REQUESTED_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) {
            bindServiceIfNeeded()
        }
    }

    override fun onStop() {
        rtspService?.detachPreview()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        rtspService = null
        super.onStop()
    }

    private fun startServiceIfNeeded() {
        if (!shouldStartService || isStartRequested) {
            return
        }
        val intent = Intent(this, RtspService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isStartRequested = true
    }

    private fun bindServiceIfNeeded() {
        if (!shouldStartService || isBound) {
            return
        }
        val intent = Intent(this, RtspService::class.java)
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateServerUi() {
        val rtspUrl = rtspService?.getRtspUrl() ?: "unknown"
        binding.txtServer.text = "RTSP Server URL:\n$rtspUrl\n(Runs in background when screen is off)"
    }

    private fun allPermissionsGranted(): Boolean {
        return hasCameraPermission()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
}
