package com.kidsafe.beacon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kidsafe.beacon.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    // Action queued until permissions are granted.
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.values.all { it }) {
            action?.invoke()
        } else {
            status("Permission denied. Cannot continue.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        binding.editToken.setText(prefs.token)
        binding.editChatId.setText(prefs.chatId)

        binding.btnSave.setOnClickListener {
            prefs.token = binding.editToken.text.toString()
            prefs.chatId = binding.editChatId.text.toString()
            status("Settings saved.")
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnTest.setOnClickListener { runTest() }
        binding.btnLocation.setOnClickListener { runLocation() }
        binding.btnPhoto.setOnClickListener { runPhoto() }
        binding.btnAudio.setOnClickListener { runAudio() }

        binding.btnStartService.setOnClickListener { startMonitoring() }
        binding.btnStopService.setOnClickListener {
            prefs.monitoringEnabled = false
            CommandService.stop(this)
            status("Remote monitoring stopped.")
        }

        // Ask for notification permission on Android 13+ (harmless if already granted).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun telegram(): TelegramClient? {
        if (!prefs.isConfigured()) {
            status("Enter bot token and chat ID first, then Save.")
            return null
        }
        return TelegramClient(prefs.token, prefs.chatId)
    }

    private fun runTest() {
        val tg = telegram() ?: return
        status("Sending test message…")
        lifecycleScope.launch {
            val ok = tg.sendMessage("KidSafe Beacon test message. Setup works!")
            status(if (ok) "Test message sent." else "Failed. Check token/chat ID and internet.")
        }
    }

    private fun runLocation() {
        val tg = telegram() ?: return
        withPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) {
            status("Getting location…")
            lifecycleScope.launch {
                val loc = LocationHelper(this@MainActivity).currentLocation()
                if (loc == null) {
                    status("Could not get location. Is GPS on?")
                    return@launch
                }
                val ok = tg.sendLocation(loc.latitude, loc.longitude)
                status(if (ok) "Location sent." else "Failed to send location.")
            }
        }
    }

    private fun runPhoto() {
        val tg = telegram() ?: return
        withPermissions(arrayOf(Manifest.permission.CAMERA)) {
            status("Taking photo…")
            lifecycleScope.launch {
                val file: File? = PhotoCapture(this@MainActivity, this@MainActivity)
                    .capture(useFrontCamera = false)
                if (file == null) {
                    status("Could not take photo.")
                    return@launch
                }
                val ok = tg.sendPhoto(file, caption = "Photo update")
                file.delete()
                status(if (ok) "Photo sent." else "Failed to send photo.")
            }
        }
    }

    private fun runAudio() {
        val tg = telegram() ?: return
        withPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) {
            status("Recording 10s of audio…")
            lifecycleScope.launch {
                val file = AudioRecorder(this@MainActivity).record(10_000)
                if (file == null) {
                    status("Could not record audio.")
                    return@launch
                }
                status("Uploading audio…")
                val ok = tg.sendAudio(file, caption = "Audio update")
                file.delete()
                status(if (ok) "Audio sent." else "Failed to send audio.")
            }
        }
    }

    private fun withPermissions(perms: Array<String>, action: () -> Unit) {
        val missing = perms.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMonitoring() {
        if (!prefs.isConfigured()) {
            status("Enter bot token and chat ID first, then Save.")
            return
        }
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        withPermissions(perms.toTypedArray()) {
            prefs.monitoringEnabled = true
            CommandService.start(this)
            status("Remote monitoring started. Send /help from your Telegram.")
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun status(message: String) {
        runOnUiThread { binding.txtStatus.text = message }
    }
}
