package com.abobo.usquevpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import usqueandroid.Usqueandroid

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val PREFS_NAME = "UsqueVpnPrefs"
        private const val KEY_SNI = "sni"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_ALLOWED_APPS = "allowed_apps"
        const val MODE_GLOBAL = "global"
        const val MODE_PER_APP = "per_app"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var ipInfoText: TextView
    private lateinit var settingsButton: Button
    private lateinit var sniText: TextView
    private lateinit var endpointText: TextView
    private lateinit var modeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        statusText = findViewById(R.id.status_text)
        connectButton = findViewById(R.id.connect_button)
        ipInfoText = findViewById(R.id.ip_info_text)
        settingsButton = findViewById(R.id.settings_button)
        sniText = findViewById(R.id.sni_text)
        endpointText = findViewById(R.id.endpoint_text)
        modeText = findViewById(R.id.mode_text)

        loadSavedSettings()

        connectButton.setOnClickListener {
            if (UsqueVpnService.isRunning) stopVpn() else startVpn()
        }

        settingsButton.setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.mode_button).setOnClickListener { showModeDialog() }
        findViewById<Button>(R.id.app_picker_button).setOnClickListener { showAppPickerDialog() }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun loadSavedSettings() {
        val savedSni = prefs.getString(KEY_SNI, "www.visa.cn") ?: "www.visa.cn"
        Usqueandroid.setSNI(savedSni)
        val savedEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        if (savedEndpoint.isNotEmpty()) {
            Usqueandroid.setEndpoint(savedEndpoint)
        }
    }

    private fun saveSettings(sni: String, endpoint: String) {
        prefs.edit()
            .putString(KEY_SNI, sni)
            .putString(KEY_ENDPOINT, endpoint)
            .apply()
    }

    private fun showModeDialog() {
        val currentMode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL
        val modes = arrayOf("全局代理 (所有应用)", "分应用代理 (仅选中的应用)")
        val checkedIndex = if (currentMode == MODE_GLOBAL) 0 else 1

        AlertDialog.Builder(this)
            .setTitle("代理模式")
            .setSingleChoiceItems(modes, checkedIndex) { dialog, which ->
                val newMode = if (which == 0) MODE_GLOBAL else MODE_PER_APP
                prefs.edit().putString(KEY_PROXY_MODE, newMode).apply()
                if (newMode == MODE_PER_APP) {
                    val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
                    if (allowedApps.isEmpty()) {
                        dialog.dismiss()
                        showAppPickerDialog()
                        return@setSingleChoiceItems
                    }
                }
                Toast.makeText(this, if (which == 0) "全局代理模式" else "分应用代理模式", Toast.LENGTH_SHORT).show()
                updateUI()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadAllApps(): List<AppItem> {
        val pm = packageManager
        val apps = mutableListOf<AppItem>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)

        // Add launcher apps
        for (ri in resolveInfos) {
            if (ri.activityInfo.packageName == packageName) continue
            apps.add(AppItem(
                name = ri.loadLabel(pm).toString(),
                packageName = ri.activityInfo.packageName,
                icon = ri.loadIcon(pm)
            ))
        }

        // Also add non-launcher apps (services, etc.) that we might want to proxy
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val existingPkgs = apps.map { it.packageName }.toSet()
        for (app in allApps) {
            if (app.packageName == packageName) continue
            if (app.packageName in existingPkgs) continue
            // Skip system packages that are unlikely to need proxying
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            apps.add(AppItem(
                name = app.loadLabel(pm).toString(),
                packageName = app.packageName,
                icon = app.loadIcon(pm)
            ))
        }

        return apps.sortedBy { it.name.lowercase() }
    }

    private fun showAppPickerDialog() {
        val allApps = loadAllApps()
        val allowedApps = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

        // Build custom dialog
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.search_input)
        val listView = dialogView.findViewById<ListView>(R.id.app_list)
        val selectAllBtn = dialogView.findViewById<Button>(R.id.select_all_btn)
        val deselectAllBtn = dialogView.findViewById<Button>(R.id.deselect_all_btn)
        val selectedCountText = dialogView.findViewById<TextView>(R.id.selected_count)

        // Track checked state
        val checkedMap = mutableMapOf<String, Boolean>()
        for (app in allApps) {
            checkedMap[app.packageName] = allowedApps.contains(app.packageName)
        }

        fun updateSelectedCount() {
            val count = checkedMap.values.count { it }
            selectedCountText.text = "已选择 $count 个应用"
        }

        fun buildFilteredList(query: String): List<AppItem> {
            if (query.isEmpty()) return allApps
            val q = query.lowercase()
            return allApps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }

        var currentList = allApps

        fun createAdapter(list: List<AppItem>): BaseAdapter {
            return object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(pos: Int) = list[pos]
                override fun getItemId(pos: Int) = pos.toLong()
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(this@MainActivity)
                        .inflate(R.layout.item_app, parent, false)
                    val app = list[pos]
                    view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
                    view.findViewById<TextView>(R.id.app_name).text = app.name
                    view.findViewById<TextView>(R.id.app_package).text = app.packageName
                    val cb = view.findViewById<CheckBox>(R.id.app_checkbox)
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = checkedMap[app.packageName] == true
                    cb.setOnCheckedChangeListener { _, isChecked ->
                        checkedMap[app.packageName] = isChecked
                        updateSelectedCount()
                    }
                    // Click entire row to toggle
                    view.setOnClickListener { cb.toggle() }
                    return view
                }
            }
        }

        listView.adapter = createAdapter(currentList)
        updateSelectedCount()

        // Search filter
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentList = buildFilteredList(s?.toString() ?: "")
                listView.adapter = createAdapter(currentList)
            }
        })

        // Select all / deselect all
        selectAllBtn.setOnClickListener {
            for (app in currentList) checkedMap[app.packageName] = true
            listView.adapter = createAdapter(currentList)
            updateSelectedCount()
        }
        deselectAllBtn.setOnClickListener {
            for (app in currentList) checkedMap[app.packageName] = false
            listView.adapter = createAdapter(currentList)
            updateSelectedCount()
        }

        AlertDialog.Builder(this)
            .setTitle("选择代理应用")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val selected = checkedMap.filter { it.value }.keys.toSet()
                prefs.edit().putStringSet(KEY_ALLOWED_APPS, selected).apply()
                Toast.makeText(this, "已选择 ${selected.size} 个应用", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val sniInput = dialogView.findViewById<EditText>(R.id.sni_input)
        val endpointInput = dialogView.findViewById<EditText>(R.id.endpoint_input)
        val configPath = "${filesDir.absolutePath}/config.json"

        sniInput.setText(prefs.getString(KEY_SNI, Usqueandroid.getSNI()))
        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        if (currentEndpoint.isNotEmpty()) {
            endpointInput.setText(currentEndpoint)
        } else {
            endpointInput.setText(Usqueandroid.getDefaultEndpoint(configPath))
        }

        AlertDialog.Builder(this)
            .setTitle("Connection Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sni = sniInput.text.toString()
                val endpoint = endpointInput.text.toString()
                saveSettings(sni, endpoint)
                Usqueandroid.setSNI(sni)
                Usqueandroid.setEndpoint(endpoint)
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                saveSettings("www.visa.cn", "")
                Usqueandroid.resetConnectionOptions()
                Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .show()
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onVpnPermissionGranted()
        }
    }

    private fun stopVpn() {
        UsqueVpnService.stop()
        val intent = Intent(this, UsqueVpnService::class.java)
        intent.action = UsqueVpnService.ACTION_DISCONNECT
        startService(intent)
        connectButton.postDelayed({ updateUI() }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                onVpnPermissionGranted()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onVpnPermissionGranted() {
        val intent = Intent(this, UsqueVpnService::class.java)
        startService(intent)
        connectButton.postDelayed({ updateUI() }, 1500)
    }

    private fun updateUI() {
        val configPath = "${filesDir.absolutePath}/config.json"

        if (UsqueVpnService.isRunning) {
            statusText.text = "Connected"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            connectButton.text = "Disconnect"
            settingsButton.isEnabled = false
        } else {
            statusText.text = "Disconnected"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            connectButton.text = "Connect"
            settingsButton.isEnabled = true
        }

        if (Usqueandroid.isRegistered(configPath)) {
            val ipv4 = Usqueandroid.getAssignedIPv4(configPath)
            val ipv6 = Usqueandroid.getAssignedIPv6(configPath)
            ipInfoText.text = "IPv4: $ipv4\nIPv6: $ipv6"
        } else {
            ipInfoText.text = "Not registered"
        }

        val currentSni = prefs.getString(KEY_SNI, Usqueandroid.getSNI()) ?: "www.visa.cn"
        sniText.text = "SNI: $currentSni"

        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        val displayEndpoint = if (currentEndpoint.isNotEmpty()) {
            currentEndpoint
        } else {
            Usqueandroid.getDefaultEndpoint(configPath)
        }
        endpointText.text = "Endpoint: $displayEndpoint"

        val mode = prefs.getString(KEY_PROXY_MODE, MODE_GLOBAL) ?: MODE_GLOBAL
        if (mode == MODE_GLOBAL) {
            modeText.text = "模式: 全局代理"
            modeText.setTextColor(getColor(android.R.color.holo_blue_light))
        } else {
            val count = (prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()).size
            modeText.text = "模式: 分应用代理 ($count 个应用)"
            modeText.setTextColor(getColor(android.R.color.holo_orange_light))
        }
    }
}
