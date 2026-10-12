package com.example.soundtransmitter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var service: PlaybackService? = null
    private lateinit var status: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as PlaybackService.LocalBinder).getService()
            service = bound
            bound.onStatus = { text -> runOnUiThread { status.text = text } }
            status.text = bound.lastStatus
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Android 13+: the foreground notification needs a runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val address = findViewById<EditText>(R.id.address)
        val port = findViewById<EditText>(R.id.port)
        val connect = findViewById<Button>(R.id.connect)
        status = findViewById(R.id.status)

        // restore the last used address and port
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        address.setText(prefs.getString("host", ""))
        port.setText(prefs.getString("port", "8765"))

        connect.setOnClickListener {
            val bound = service
            if (bound != null && bound.isConnected) {
                bound.disconnect()
            } else {
                val host = address.text.toString()
                val portText = port.text.toString()
                prefs.edit().putString("host", host).putString("port", portText).apply()

                // ask once to be exempt from battery optimization, so the service isn't killed
                val power = getSystemService(PowerManager::class.java)
                if (!prefs.getBoolean("batteryAsked", false) &&
                    !power.isIgnoringBatteryOptimizations(packageName)
                ) {
                    prefs.edit().putBoolean("batteryAsked", true).apply()
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }

                startForegroundService(Intent(this, PlaybackService::class.java))
                bound?.connect(host, portText)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        service?.onStatus = null
        unbindService(connection)
        service = null
    }
}
