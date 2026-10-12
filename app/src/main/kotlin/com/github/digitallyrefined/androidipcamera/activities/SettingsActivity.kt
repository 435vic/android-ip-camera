package com.github.digitallyrefined.androidipcamera.activities

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.preference.*
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.CertificateHelper
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import com.github.digitallyrefined.androidipcamera.helpers.RecordingsHelper
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment()).commit()
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var secure: SecureStorage
        private var service: StreamingService? = null
        private var bound = false
        private val handler = Handler(Looper.getMainLooper())
        private val status = object : Runnable {
            override fun run() {
                findPreference<Preference>("connection_status")?.summary =
                    service?.streamingServerHelper?.listenerStatus ?: "Connecting to service…"
                handler.postDelayed(this, 500)
            }
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as StreamingService.LocalBinder).getService()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }

        override fun onStart() {
            super.onStart()
            bound = requireContext().bindService(Intent(requireContext(), StreamingService::class.java), connection, 0)
            handler.post(status)
        }
        override fun onStop() {
            handler.removeCallbacks(status)
            if (bound) requireContext().unbindService(connection)
            bound = false
            service = null
            super.onStop()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            secure = SecureStorage(requireContext())
            listOf("username", "password", "certificate_password").forEach {
                findPreference<EditTextPreference>(it)?.isPersistent = false
            }
            updateSummaries()
            updateVisibility()
            listOf("enable_auth", "tls_version").forEach { key ->
                findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    handler.post { updateVisibility(); updateSummaries() }
                    true
                }
            }
            findPreference<Preference>("apply_connection")?.setOnPreferenceClickListener {
                requireContext().startService(Intent(requireContext(), StreamingService::class.java).apply {
                    action = StreamingService.ACTION_RESTART_SERVER
                })
                findPreference<Preference>("connection_status")?.summary = "Applying connection settings…"
                true
            }
            findPreference<Preference>("connection_status")?.setOnPreferenceClickListener {
                val prefs = preferenceManager.sharedPreferences!!
                val scheme = if (prefs.getString("tls_version", "1.3") == "disabled") "http" else "https"
                val port = prefs.getString("server_port", "4444")
                val addresses = java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { network ->
                    network.inetAddresses.toList().filter { !it.isLoopbackAddress }.map { address ->
                        val host = address.hostAddress ?: ""
                        "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"
                    }
                }
                message("Connection", (listOf(service?.streamingServerHelper?.listenerStatus ?: "Listener unavailable") + addresses).joinToString("\n\n"))
                true
            }
            findPreference<Preference>("certificate_path")?.setOnPreferenceClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 1)
                true
            }
            findPreference<Preference>("test_certificate")?.setOnPreferenceClickListener {
                val path = preferenceManager.sharedPreferences?.getString("certificate_path", null)
                val password = secure.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")
                val valid = !password.isNullOrEmpty() && if (path != null)
                    InputValidator.validateCertificateUsability(requireContext(), Uri.parse(path), password)
                else InputValidator.validateBuiltInCertificate(requireContext(), password)
                message("Certificate validation", if (valid) "Certificate configuration is valid." else
                    "Validation failed. Check the certificate file and certificate password.")
                true
            }
            findPreference<Preference>("recording_storage_uri")?.setOnPreferenceClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, 2)
                true
            }
            findPreference<Preference>("reset_recording_storage")?.setOnPreferenceClickListener {
                RecordingsHelper.customStorageUri(requireContext())?.let { uri ->
                    try { requireContext().contentResolver.releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
                }
                preferenceManager.sharedPreferences!!.edit().remove(RecordingsHelper.PREF_RECORDING_STORAGE_URI).apply()
                updateSummaries()
                true
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference !is EditTextPreference) { super.onDisplayPreferenceDialog(preference); return }
            val key = preference.key
            val secretKey = when (key) {
                "username" -> SecureStorage.KEY_USERNAME
                "password" -> SecureStorage.KEY_PASSWORD
                "certificate_password" -> SecureStorage.KEY_CERT_PASSWORD
                else -> null
            }
            val password = key == "password" || key == "certificate_password"
            val input = EditText(requireContext()).apply {
                setSingleLine(true)
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    key == "server_port" -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                if (password) {
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    hint = "Blank keeps current"
                    isSaveEnabled = false
                    if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                } else setText(if (secretKey != null) secure.getSecureString(secretKey, "") else preference.text)
                setSelectAllOnFocus(true)
            }
            val padding = (24 * resources.displayMetrics.density).toInt()
            val errorText = android.widget.TextView(requireContext()).apply {
                setTextColor(android.graphics.Color.rgb(255, 180, 171))
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                visibility = android.view.View.GONE
            }
            val fields = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(input)
                addView(errorText)
            }
            fun showError(message: String) {
                errorText.text = message
                errorText.visibility = android.view.View.VISIBLE
            }
            val dialog = AlertDialog.Builder(requireContext()).setTitle(preference.title)
                .setMessage(preference.dialogMessage).setView(fields, padding, 8, padding, 8)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().let { if (key == "bind_address") it.trim() else it }
                    if (password && value.isEmpty()) { dialog.dismiss(); return@setOnClickListener }
                    val error = when {
                        key == "username" && !InputValidator.isValidUsername(value) -> "Use 1–50 letters, numbers, hyphens or underscores."
                        key == "password" && !InputValidator.isValidPassword(value) -> "Use 8–128 characters with uppercase, lowercase and a number."
                        key == "certificate_password" && !InputValidator.isValidCertificatePassword(value) -> "Use at most 256 characters."
                        key == "server_port" && value.toIntOrNull() !in 1..65535 -> "Use a port from 1 to 65535."
                        key == "bind_address" && !validAddress(value) -> "Enter a numeric IP address. Use 0.0.0.0 for all IPv4 interfaces, or :: for all IPv6 interfaces."
                        else -> null
                    }
                    if (error != null) { showError(error); return@setOnClickListener }
                    if (secretKey != null) {
                        if (!secure.putSecureString(secretKey, value)) { showError("Could not save securely. Try again."); return@setOnClickListener }
                    } else preference.text = value
                    updateSummaries()
                    dialog.dismiss()
                }
            }
            dialog.show()
        }

        private fun validAddress(value: String): Boolean = try {
            (value == "0.0.0.0" || android.util.Patterns.IP_ADDRESS.matcher(value).matches() ||
                (value.contains(':') && value.substringBefore('%').matches(Regex("[0-9a-fA-F:.]+")))) &&
                java.net.InetAddress.getByName(value) != null
        } catch (_: Exception) { false }

        private fun updateVisibility() {
            val auth = findPreference<CheckBoxPreference>("enable_auth")?.isChecked == true
            listOf("username", "password").forEach { findPreference<Preference>(it)?.isVisible = auth }
            val tls = findPreference<ListPreference>("tls_version")?.value != "disabled"
            listOf("certificate_path", "certificate_password", "test_certificate").forEach { findPreference<Preference>(it)?.isVisible = tls }
        }

        private fun updateSummaries() {
            val prefs = preferenceManager.sharedPreferences!!
            findPreference<Preference>("username")?.summary = secure.getSecureString(SecureStorage.KEY_USERNAME, "Not set")
            mapOf("password" to SecureStorage.KEY_PASSWORD, "certificate_password" to SecureStorage.KEY_CERT_PASSWORD).forEach { (key, storageKey) ->
                findPreference<Preference>(key)?.summary = if (secure.getSecureString(storageKey, "").isNullOrEmpty()) "Not set" else "Set"
            }
            findPreference<Preference>("server_port")?.summary = prefs.getString("server_port", "4444")
            val bind = prefs.getString("bind_address", "0.0.0.0") ?: "0.0.0.0"
            findPreference<Preference>("bind_address")?.summary = if (bind == "0.0.0.0" || bind == "::") "$bind — all interfaces" else "$bind — this address only"
            findPreference<ListPreference>("tls_version")?.let { it.summary = it.entry }
            findPreference<Preference>("certificate_path")?.summary = prefs.getString("certificate_path", null)?.let { Uri.parse(it).lastPathSegment } ?: "Device certificate"
            val uri = RecordingsHelper.customStorageUri(requireContext())
            findPreference<Preference>("recording_storage_uri")?.summary = if (uri == null) RecordingsHelper.DEFAULT_RELATIVE_PATH else DocumentFile.fromTreeUri(requireContext(), uri)?.name ?: uri.toString()
        }

        private fun message(title: String, text: String) {
            AlertDialog.Builder(requireContext()).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (resultCode != Activity.RESULT_OK) return
            val uri = data?.data ?: return
            if (requestCode == 1) {
                if (!CertificateHelper.importCertificate(requireContext(), uri, secure.getSecureString(SecureStorage.KEY_CERT_PASSWORD, ""))) {
                    message("Certificate import failed", "Check the password and select a PKCS12 file under 10 MB.")
                }
            } else if (requestCode == 2) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    preferenceManager.sharedPreferences!!.edit().putString(RecordingsHelper.PREF_RECORDING_STORAGE_URI, uri.toString()).apply()
                } catch (e: Exception) { message("Folder unavailable", e.message ?: "Could not use that folder.") }
            }
            updateSummaries()
        }
    }
}
