package com.cid0980.connectwin

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvTunnelUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnStartStop: Button
    private lateinit var permsContainer: LinearLayout
    private lateinit var tvAudit: TextView
    private lateinit var etNamedToken: EditText
    private lateinit var etCustomHost: EditText

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeState.init(this)
        setContentView(buildUi())
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsUi()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cardBg(strokeColor: Int = Color.parseColor("#243044")): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#151C28"))
            setStroke(dp(1), strokeColor)
        }

    private fun pillBg(fillColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(fillColor)
        }

    private fun makeCard(strokeColor: Int = Color.parseColor("#243044")): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(strokeColor)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

    private fun makeButton(label: String, bgHex: String, textHex: String = "#0B0F17", onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg(Color.parseColor(bgHex))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        scroll.addView(root)

        // Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Connect-win"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        titleCol.addView(TextView(this).apply {
            text = "Mobile Agent Bridge · v${BridgeState.VERSION}"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        tvStatusBadge = TextView(this).apply {
            text = "STOPPED"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
            background = pillBg(Color.parseColor("#334155"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        headerRow.addView(titleCol)
        headerRow.addView(tvStatusBadge)
        root.addView(headerRow)

        // 1. Live Connection & Control Card
        val connCard = makeCard(Color.parseColor("#06B6D4"))
        tvTunnelUrl = TextView(this).apply {
            text = "Tunnel URL: Not running"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#38BDF8"))
            setTextIsSelectable(true)
        }
        tvToken = TextView(this).apply {
            text = "Token: ${BridgeState.getOrCreateToken(this@MainActivity)}"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(6), 0, dp(6))
            setTextIsSelectable(true)
        }
        tvStats = TextView(this).apply {
            text = "Port: 127.0.0.1:${BridgeState.HTTP_PORT} · Requests: 0"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(12))
        }
        connCard.addView(tvTunnelUrl)
        connCard.addView(tvToken)
        connCard.addView(tvStats)

        btnStartStop = makeButton("Start Bridge & Tunnel", "#10B981") {
            if (BridgeState.snapshot.value.running) {
                BridgeService.stop(this)
            } else {
                if (Build.VERSION.SDK_INT >= 33 && !PhoneController.hasPerm(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                BridgeService.start(this)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        connCard.addView(btnStartStop)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val btnCopy = makeButton("Copy URL + Token", "#06B6D4") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Connect-win", BridgeService.buildArenaSnippet(this)))
            Toast.makeText(this, "Copied! Paste into Arena chat.", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        val btnRotate = makeButton("New Token", "#334155", "#F8FAFC") {
            val newTok = BridgeState.rotateToken(this)
            tvToken.text = "Token: $newTok"
            Toast.makeText(this, "Token rotated", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btnRow.addView(btnCopy)
        btnRow.addView(btnRotate)
        connCard.addView(btnRow)
        root.addView(connCard)

        // Local, best-effort monitor; no conversation content is saved or transmitted.
        val arenaCard = makeCard(Color.parseColor("#A78BFA"))
        arenaCard.addView(TextView(this).apply {
            text = "Arena completion notification"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        arenaCard.addView(Switch(this).apply {
            text = "Notify when Arena finishes"
            setTextColor(Color.parseColor("#CBD5E1"))
            isChecked = ArenaCompletionMonitor.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                ArenaCompletionMonitor.setEnabled(this@MainActivity, checked)
                if (checked && Build.VERSION.SDK_INT >= 33 && !PhoneController.hasPerm(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        })
        arenaCard.addView(TextView(this).apply {
            text = "Best effort: keep the Arena agent page visible in Chrome while it works. Accessibility and notifications must be enabled. The detector reads only the page address and composer controls locally; it does not store chat content. It may miss background/locked pages or alert when generation is stopped or paused. Sound follows Android notification and Do Not Disturb settings."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(8))
        })
        arenaCard.addView(makeButton("Test notification sound", "#334155", "#F8FAFC") {
            if (Build.VERSION.SDK_INT >= 33 && !PhoneController.hasPerm(this, Manifest.permission.POST_NOTIFICATIONS)) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                Toast.makeText(this, "Allow notifications, then tap Test again.", Toast.LENGTH_LONG).show()
            } else {
                val sent = ArenaCompletionMonitor.notify(this, test = true)
                Toast.makeText(this, if (sent) "Test sent. Check notification sound settings if silent." else "Enable Connect-win notifications in Android settings.", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(arenaCard)

        // 2. Android 13-16 Restricted Settings Helper Card
        if (Build.VERSION.SDK_INT >= 33) {
            val restrictCard = makeCard(Color.parseColor("#F59E0B"))
            restrictCard.addView(TextView(this).apply {
                text = "Android 13–16 Sideload Unlock (One-Time)"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FBBF24"))
            })
            restrictCard.addView(TextView(this).apply {
                text = "If Accessibility or Notification toggle says 'Restricted setting', tap below -> tap ⋮ (top-right menu) -> 'Allow restricted settings'."
                textSize = 12.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(0, dp(4), 0, dp(10))
            })
            restrictCard.addView(makeButton("Open Connect-win App Info", "#F59E0B") {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(i)
            })
            root.addView(restrictCard)
        }

        // 3. Interactive Permissions Checklist Card
        val permCard = makeCard()
        val permHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        permHeader.addView(TextView(this).apply {
            text = "Permissions Control Center"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        permHeader.addView(makeButton("Grant Runtime All", "#1E293B", "#38BDF8") {
            requestAllRuntimePerms()
        })
        permCard.addView(permHeader)

        permsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        permCard.addView(permsContainer)
        root.addView(permCard)

        // 4. Tunnel & Startup Settings Card
        val cfgCard = makeCard()
        cfgCard.addView(TextView(this).apply {
            text = "Tunnel & Boot Options"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(8))
        })

        val swBoot = Switch(this).apply {
            text = "Auto-start Bridge on Phone Boot"
            setTextColor(Color.parseColor("#CBD5E1"))
            isChecked = BridgeState.isAutoBoot(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                BridgeState.setAutoBoot(this@MainActivity, checked)
            }
            setPadding(0, dp(4), 0, dp(10))
        }
        cfgCard.addView(swBoot)

        cfgCard.addView(TextView(this).apply {
            text = "Named Cloudflare Tunnel Token (leave empty for free Quick Tunnel):"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        etNamedToken = EditText(this).apply {
            hint = "eyJhIjoi... (optional permanent tunnel token)"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(BridgeState.getNamedTunnelToken(this@MainActivity))
        }
        cfgCard.addView(etNamedToken)

        etCustomHost = EditText(this).apply {
            hint = "https://mobile.yourdomain.com (optional display URL)"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(BridgeState.getCustomHostname(this@MainActivity))
        }
        cfgCard.addView(etCustomHost)

        cfgCard.addView(makeButton("Save Tunnel Config & Reconnect", "#334155", "#F8FAFC") {
            BridgeState.setNamedTunnelToken(this, etNamedToken.text.toString())
            BridgeState.setCustomHostname(this, etCustomHost.text.toString())
            if (BridgeState.snapshot.value.running) {
                startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_RESTART_TUNNEL))
            }
            Toast.makeText(this, "Tunnel settings saved", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        root.addView(cfgCard)

        // 5. Live Audit Log Card
        val auditCard = makeCard()
        auditCard.addView(TextView(this).apply {
            text = "Live Audit Log"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(6))
        })
        tvAudit = TextView(this).apply {
            text = "No activity yet."
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
        }
        auditCard.addView(tvAudit)
        root.addView(auditCard)

        return scroll
    }

    private fun observeState() {
        lifecycleScope.launch {
            BridgeState.snapshot.collectLatest { snap ->
                if (snap.running) {
                    val connected = snap.tunnelUrl != null || snap.tunnelStatus.startsWith("Connected")
                    tvStatusBadge.text = if (connected) "LIVE" else "STARTING"
                    tvStatusBadge.background = pillBg(
                        Color.parseColor(if (connected) "#10B981" else "#F59E0B")
                    )
                    tvTunnelUrl.text = "URL: ${snap.tunnelUrl ?: snap.tunnelStatus}"
                    btnStartStop.text = "Stop Bridge"
                    btnStartStop.background = pillBg(Color.parseColor("#EF4444"))
                    btnStartStop.setTextColor(Color.WHITE)
                } else {
                    tvStatusBadge.text = "STOPPED"
                    tvStatusBadge.background = pillBg(Color.parseColor("#334155"))
                    tvTunnelUrl.text = "Tunnel URL: Stopped"
                    btnStartStop.text = "Start Bridge & Tunnel"
                    btnStartStop.background = pillBg(Color.parseColor("#10B981"))
                    btnStartStop.setTextColor(Color.parseColor("#0B0F17"))
                }
                tvToken.text = "Token: ${BridgeState.getOrCreateToken(this@MainActivity)}"
                tvStats.text = "Local: 127.0.0.1:${BridgeState.HTTP_PORT} · Status: ${snap.tunnelStatus} · Requests: ${snap.requestCount}"
            }
        }

        lifecycleScope.launch {
            BridgeState.auditLines.collectLatest { lines ->
                tvAudit.text = if (lines.isEmpty()) "No activity yet."
                else lines.takeLast(25).reversed().joinToString("\n")
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun refreshPermissionsUi() {
        permsContainer.removeAllViews()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        data class PermRow(val title: String, val sub: String, val granted: Boolean, val act: () -> Unit)

        val rows = listOf(
            PermRow(
                "1. Accessibility Service (Required)",
                "Touch gestures, UI DOM tree, zero-popup screenshots",
                PhoneController.isAccessibilityEnabled(this)
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },

            PermRow(
                "2. Notification Access",
                "Read alerts/OTPs, inline WhatsApp/SMS replies, dismiss",
                PhoneController.isNotificationListenerEnabled(this)
            ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },

            PermRow(
                "3. All Files Access (/sdcard)",
                "Upload, download, list & edit phone storage",
                Environment.isExternalStorageManager()
            ) {
                val i = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                runCatching { startActivity(i) }.onFailure {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            },

            PermRow(
                "4. Unrestricted Battery",
                "Prevents Android from sleeping tunnel when locked",
                pm.isIgnoringBatteryOptimizations(packageName)
            ) {
                val i = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(i)
            },

            PermRow(
                "5. Display Over Other Apps",
                "Allows launching apps reliably from background",
                Settings.canDrawOverlays(this)
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            },

            PermRow(
                "6. Modify System Settings",
                "Screen brightness & system toggles",
                Settings.System.canWrite(this)
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                )
            },

            PermRow(
                "7. Install Unknown Apps",
                "Trigger APK installations & self-updates",
                packageManager.canRequestPackageInstalls()
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            },

            PermRow(
                "8. Location, Camera, SMS, Contacts & Phone",
                "GPS, headless Camera2 photo, OTP/SMS, calls & mic",
                PhoneController.hasPerm(this, Manifest.permission.ACCESS_FINE_LOCATION) &&
                    PhoneController.hasPerm(this, Manifest.permission.CAMERA) &&
                    PhoneController.hasPerm(this, Manifest.permission.READ_SMS) &&
                    PhoneController.hasPerm(this, Manifest.permission.READ_CONTACTS)
            ) { requestAllRuntimePerms() }
        )

        for (r in rows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
            }
            textCol.addView(TextView(this).apply {
                text = r.title
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F8FAFC"))
            })
            textCol.addView(TextView(this).apply {
                text = r.sub
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
            })

            val badge = makeButton(
                if (r.granted) "READY" else "GRANT",
                if (r.granted) "#064E3B" else "#06B6D4",
                if (r.granted) "#34D399" else "#0B0F17"
            ) { r.act() }

            rowView.addView(textCol)
            rowView.addView(badge)
            permsContainer.addView(rowView)
        }
    }

    private fun requestAllRuntimePerms() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }
}
