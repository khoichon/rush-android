package com.khoichon.rush

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

/** Configuration + control screen: permissions, start/stop, "call Rush now", and the options. */
class MainActivity : ComponentActivity() {
    private lateinit var cfg: Config
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var notifButton: Button
    private lateinit var toggleButton: Button
    private lateinit var callButton: Button
    private lateinit var jumpscareButton: Button
    private lateinit var adminStatus: TextView
    private lateinit var adminButton: Button
    private lateinit var deathGroup: RadioGroup
    private lateinit var guidingRadio: RadioButton

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- state

    private fun overlayGranted() = Settings.canDrawOverlays(this)

    private fun notifGranted() =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun adminComponent() = ComponentName(this, RushAdmin::class.java)

    private fun adminActive() =
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent())

    private fun refresh() {
        val running = RushService.running
        val overlay = overlayGranted()
        overlayStatus.text = if (overlay) "Display over other apps: allowed" else "Display over other apps: NOT allowed (required)"
        overlayStatus.setTextColor(if (overlay) 0xFFB8F5B8.toInt() else 0xFFFF9A9A.toInt())
        overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE
        notifButton.visibility = if (notifGranted()) View.GONE else View.VISIBLE
        toggleButton.text = if (running) "Stop RUSH" else "Start RUSH"
        toggleButton.isEnabled = running || overlay
        callButton.isEnabled = running
        jumpscareButton.visibility = if (cfg.testMode && running) View.VISIBLE else View.GONE
        val admin = adminActive()
        adminStatus.text = if (admin) "Screen-lock permission: granted" else "Screen-lock permission: not granted"
        adminButton.text = if (admin) "Remove screen-lock permission" else "Grant screen-lock permission"
        val guiding = GuidingLight.isInstalled(this)
        guidingRadio.isEnabled = guiding
        guidingRadio.alpha = if (guiding) 1f else 0.4f
        guidingRadio.text = if (guiding) "Show the Guiding Light death screen"
        else "Show the Guiding Light death screen (install the Guiding Light app first)"
        if (!guiding && cfg.deathAction == DeathAction.GUIDING_LIGHT) {
            cfg.deathAction = DeathAction.NONE // the helper app is gone
            deathGroup.check(R.id.death_none)
        }
        if (!admin && cfg.deathAction == DeathAction.LOCK) {
            cfg.deathAction = DeathAction.NONE // can't lock without the permission
            deathGroup.check(R.id.death_none)
        }
    }

    private fun refreshSoon() = window.decorView.postDelayed({ refresh() }, 350)

    private fun notifyService() {
        if (RushService.running) {
            startService(Intent(this, RushService::class.java).setAction(RushService.ACTION_CONFIG_CHANGED))
        }
    }

    // ---------------------------------------------------------------- actions

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun toggleService() {
        if (RushService.running) {
            startService(Intent(this, RushService::class.java).setAction(RushService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(
                this, Intent(this, RushService::class.java).setAction(RushService.ACTION_START)
            )
        }
        refreshSoon()
    }

    private fun toggleAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (adminActive()) {
            dpm.removeActiveAdmin(adminComponent())
            refreshSoon()
        } else {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Only used to lock the screen when Rush catches you (if you pick that consequence)."
                    )
            )
        }
    }

    private fun onDeathChoice(choice: DeathAction, previous: DeathAction) {
        when (choice) {
            DeathAction.NONE -> cfg.deathAction = choice
            DeathAction.GUIDING_LIGHT -> {
                if (GuidingLight.isInstalled(this)) {
                    cfg.deathAction = choice
                } else {
                    deathGroup.check(idFor(previous))
                }
            }
            DeathAction.HOME -> confirm(
                "Go to the home screen when Rush catches you?",
                onYes = { cfg.deathAction = choice },
                onNo = { deathGroup.check(idFor(previous)) }
            )
            DeathAction.LOCK -> {
                if (!adminActive()) {
                    AlertDialog.Builder(this)
                        .setMessage("Locking the screen needs the screen-lock permission first. Grant it below, then pick this again.")
                        .setPositiveButton("OK", null)
                        .show()
                    deathGroup.check(idFor(previous))
                } else {
                    confirm(
                        "Lock the phone when Rush catches you?",
                        onYes = { cfg.deathAction = choice },
                        onNo = { deathGroup.check(idFor(previous)) }
                    )
                }
            }
        }
    }

    private fun confirm(message: String, onYes: () -> Unit, onNo: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No") { _, _ -> onNo() }
            .setOnCancelListener { onNo() }
            .show()
    }

    private fun idFor(a: DeathAction) = when (a) {
        DeathAction.NONE -> R.id.death_none
        DeathAction.HOME -> R.id.death_home
        DeathAction.LOCK -> R.id.death_lock
        DeathAction.GUIDING_LIGHT -> R.id.death_guiding
    }

    // ---------------------------------------------------------------- UI building

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }

        col.addView(text("RUSH", 34f, bold = true, color = 0xFFFFFFFF.toInt()))
        col.addView(text("A fan-made tribute to Rush from Doors. Android edition: Rush comes through your screen, over whatever app you are in.", 13f, margin = 4))
        col.addView(text(
            "When the lights flicker, Rush is coming. Tap the round button at the bottom to hide in the closet " +
                "before he passes. If you are not hiding when he reaches you, he catches you. " +
                "The notification's Stop button always gets you out.",
            12f, margin = 8, color = 0xCCFFFFFF.toInt()
        ))

        // ---- permissions ----
        col.addView(header("Permissions"))
        overlayStatus = text("", 13f)
        col.addView(overlayStatus)
        overlayButton = button("Allow display over other apps") { requestOverlay() }
        col.addView(overlayButton)
        notifButton = button("Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        col.addView(notifButton)

        // ---- control ----
        col.addView(header("Control"))
        toggleButton = button("Start RUSH") { toggleService() }
        col.addView(toggleButton)
        callButton = button("Call Rush now") {
            startService(Intent(this, RushService::class.java).setAction(RushService.ACTION_SPAWN))
        }
        col.addView(callButton)
        jumpscareButton = button("Test the jumpscare (test mode)") {
            startService(Intent(this, RushService::class.java).setAction(RushService.ACTION_JUMPSCARE))
        }
        col.addView(jumpscareButton)
        col.addView(text("Tip: add the \"Call RUSH\" tile to your Quick Settings.", 11f, margin = 4, color = 0xAAFFFFFF.toInt()))

        // ---- behavior ----
        col.addView(header("Behavior"))
        col.addView(switchRow("Spawn automatically", cfg.spawnAutomatically) { cfg.spawnAutomatically = it; notifyService() })
        col.addView(numberRow("Min delay (s)", cfg.minSpawnDelay.toString(), decimal = false) { cfg.minSpawnDelay = it.toInt(); notifyService() })
        col.addView(numberRow("Max delay (s)", cfg.maxSpawnDelay.toString(), decimal = false) { cfg.maxSpawnDelay = it.toInt(); notifyService() })
        col.addView(numberRow("Speed (1-100)", cfg.rushSpeed.toString(), decimal = false) { cfg.rushSpeed = it.toInt() })
        col.addView(numberRow("Fake-out chance (0-100 %)", cfg.fakeoutChance.toString(), decimal = false) { cfg.fakeoutChance = it.toInt() })
        col.addView(switchRow("Vibration", cfg.haptics) { cfg.haptics = it })
        col.addView(switchRow("Start RUSH when the phone boots", cfg.startOnBoot) { cfg.startOnBoot = it })

        // ---- when it catches you ----
        col.addView(header("When Rush catches you"))
        col.addView(text("Off by default. Skipped entirely in test mode.", 11f, color = 0xAAFFFFFF.toInt()))
        deathGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fun radio(id: Int, label: String) = RadioButton(this).apply {
            this.id = id; text = label; setTextColor(Color.WHITE)
        }
        deathGroup.addView(radio(R.id.death_none, "Nothing"))
        deathGroup.addView(radio(R.id.death_home, "Go to the home screen"))
        deathGroup.addView(radio(R.id.death_lock, "Lock the screen"))
        guidingRadio = radio(R.id.death_guiding, "Show the Guiding Light death screen")
        deathGroup.addView(guidingRadio)
        deathGroup.check(idFor(cfg.deathAction))
        deathGroup.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.death_home -> DeathAction.HOME
                R.id.death_lock -> DeathAction.LOCK
                R.id.death_guiding -> DeathAction.GUIDING_LIGHT
                else -> DeathAction.NONE
            }
            if (choice != cfg.deathAction) {
                val prev = cfg.deathAction
                onDeathChoice(choice, prev)
            }
        }
        col.addView(deathGroup)
        adminStatus = text("", 12f)
        col.addView(adminStatus)
        adminButton = button("Grant screen-lock permission") { toggleAdmin() }
        col.addView(adminButton)
        col.addView(text("A phone can't be shut down by an app, so there is no \"crash\" option here.", 11f, margin = 4, color = 0xAAFFFFFF.toInt()))

        // ---- test mode ----
        col.addView(header("Testing"))
        col.addView(switchRow("Test mode (skip consequences)", cfg.testMode) { cfg.testMode = it; refresh() })

        col.addView(text(
            "Doors and Rush belong to LSPLASH. Unofficial, non-commercial fan project; sprites and sounds " +
                "are taken from the Doors wiki. Android port by khoichon.",
            11f, margin = 20, color = 0x99FFFFFF.toInt()
        ))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B0B16.toInt(), 0xFF15152A.toInt())
            )
            addView(col)
        }
        return scroll
    }

    private fun text(
        s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE, margin: Int = 0
    ) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(margin), 0, 0)
    }

    private fun header(s: String) = text(s, 16f, bold = true, color = 0xFFFFFFFF.toInt(), margin = 22)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isChecked = initial
        setPadding(0, dp(8), 0, dp(4))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun numberRow(label: String, initial: String, decimal: Boolean, onValue: (String) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        row.addView(
            TextView(this).apply { text = label; textSize = 13f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val field = EditText(this).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
            setBackgroundColor(0x33FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.END
            doAfterTextChanged { s ->
                val v = s?.toString()?.trim().orEmpty()
                // only accept something that parses; Config clamps it into range
                val ok = if (decimal) v.toFloatOrNull() != null else v.toIntOrNull() != null
                if (ok) onValue(v)
            }
        }
        row.addView(field, LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }
}
