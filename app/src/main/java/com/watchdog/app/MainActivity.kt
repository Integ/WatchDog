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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    private var cameraOptions: List<CameraOption> = emptyList()
    private var isUpdatingCameraSelection = false

    private val cameraAdapter by lazy {
        ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, mutableListOf()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private val cameraStateListener = object : RtspService.CameraStateListener {
        override fun onCameraOptionsChanged(
            options: List<CameraOption>,
            selectedCameraId: String?
        ) {
            renderCameraOptions(options, selectedCameraId)
        }
    }

    private val videoInfoListener = object : RtspService.VideoInfoListener {
        override fun onVideoInfoChanged(info: VideoStreamInfo) {
            binding.txtStreamInfo.text = "${info.width}x${info.height} @ ${info.frameRate}fps"
            binding.txtStreamInfo.visibility = View.VISIBLE
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RtspService.LocalBinder ?: return
            rtspService = binder.getService()
            isBound = true
            rtspService?.setCameraStateListener(cameraStateListener)
            renderCameraOptions(
                rtspService?.getAvailableCameras().orEmpty(),
                rtspService?.getSelectedCameraId()
            )
            updateServerUi()
            rtspService?.attachPreview(binding.previewView.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rtspService?.setCameraStateListener(null)
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
        setupCameraSelector()

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
        rtspService?.setCameraStateListener(null)
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

    private fun setupCameraSelector() {
        binding.spinnerCamera.adapter = cameraAdapter
        binding.spinnerCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isUpdatingCameraSelection) {
                    return
                }

                val option = cameraOptions.getOrNull(position) ?: return
                val service = rtspService ?: return
                if (option.id == service.getSelectedCameraId()) {
                    return
                }

                if (service.setSelectedCamera(option.id)) {
                    updateServerUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        renderCameraOptions(emptyList(), null)
    }

    private fun renderCameraOptions(
        options: List<CameraOption>,
        selectedCameraId: String?
    ) {
        cameraOptions = options
        isUpdatingCameraSelection = true
        cameraAdapter.clear()

        when {
            options.isEmpty() -> {
                cameraAdapter.add(getString(R.string.camera_loading))
                binding.spinnerCamera.isEnabled = false
                binding.spinnerCamera.alpha = 0.65f
            }

            else -> {
                cameraAdapter.addAll(options.map(CameraOption::label))
                binding.spinnerCamera.isEnabled = options.size > 1
                binding.spinnerCamera.alpha = 1f

                val selectedIndex = options.indexOfFirst { it.id == selectedCameraId }
                    .takeIf { it >= 0 }
                    ?: 0
                binding.spinnerCamera.setSelection(selectedIndex, false)
            }
        }

        cameraAdapter.notifyDataSetChanged()
        isUpdatingCameraSelection = false
        updateServerUi()
    }

    private fun updateServerUi() {
        val rtspUrl = rtspService?.getRtspUrl() ?: "unknown"
        val selectedCameraLabel = cameraOptions
            .firstOrNull { it.id == rtspService?.getSelectedCameraId() }
            ?.label
            ?: getString(
                if (cameraOptions.isEmpty()) {
                    R.string.camera_loading
                } else {
                    R.string.camera_unavailable
                }
            )

        binding.txtServer.text =
            "RTSP Server URL:\n$rtspUrl\nCamera: $selectedCameraLabel\n(Runs in background when screen is off)"
    }

    private fun allPermissionsGranted(): Boolean {
        return hasCameraPermission()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
}
