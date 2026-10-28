package com.maintiflow.pro.ui.master

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.master.MasterController
import com.maintiflow.pro.master.MasterService
import com.maintiflow.pro.ui.components.BigButton
import com.maintiflow.pro.ui.components.SectionCard
import com.maintiflow.server.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Écran du téléphone maître : mise en service, marche / arrêt du serveur, adresse à donner aux autres. */
@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterScreen(repo: Repository, master: MasterController, onBack: () -> Unit, onUseThisPhone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by master.state.collectAsState()
    var initialized by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(master.addresses()) }

    // L'écran reste allumé tant que cette page est ouverte (téléphone maître posé sur son chargeur).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { initialized = withContext(Dispatchers.IO) { master.isInitialized() } }
    LaunchedEffect(state.running) { addresses = master.addresses() }

    fun launchTask(block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (e: ApiException) {
                message = e.detail
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            }
            busy = false
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { MasterService.start(context) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) launchTask {
            withContext(Dispatchers.IO) { master.restoreBackup(uri) }
            message = context.getString(R.string.master_restored)
            initialized = true
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.master_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.master_intro), style = MaterialTheme.typography.bodyMedium)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            when (initialized) {
                null -> Text("…")
                false -> SetupForm(busy) { password, company, demo ->
                    launchTask {
                        withContext(Dispatchers.IO) { master.initialize(password, company, demo) }
                        initialized = true
                    }
                }
                true -> {
                    SectionCard(stringResource(R.string.master_server)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (state.running) R.string.master_running else R.string.master_stopped),
                                Modifier.weight(1f), fontWeight = FontWeight.Bold,
                                color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Switch(state.running, enabled = !busy, onCheckedChange = { on ->
                                if (on) {
                                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else MasterService.start(context)
                                } else {
                                    MasterService.stop(context)
                                }
                            })
                        }
                        if (state.running) {
                            Text(stringResource(R.string.master_address_label))
                            if (addresses.isEmpty()) {
                                Text(stringResource(R.string.master_no_wifi), color = MaterialTheme.colorScheme.error)
                            }
                            SelectionContainer {
                                Column { addresses.forEach { Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
                            }
                            Text(stringResource(R.string.master_local_hint, MasterController.LOCAL_URL), style = MaterialTheme.typography.bodySmall)
                            BigButton(stringResource(R.string.master_use_this_phone)) {
                                repo.session.serverUrl = MasterController.LOCAL_URL
                                onUseThisPhone()
                            }
                        }
                    }

                    SectionCard(stringResource(R.string.master_reliability)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var auto by remember { mutableStateOf(master.autoStart) }
                            Text(stringResource(R.string.master_autostart), Modifier.weight(1f))
                            Checkbox(auto, { auto = it; master.autoStart = it })
                        }
                        val pm = context.getSystemService(PowerManager::class.java)
                        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                            Text(stringResource(R.string.master_battery_hint), style = MaterialTheme.typography.bodySmall)
                            BigButton(stringResource(R.string.master_battery_button), primary = false) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")))
                                }
                            }
                        }
                        Text(stringResource(R.string.master_charger_hint), style = MaterialTheme.typography.bodySmall)
                    }

                    SectionCard(stringResource(R.string.master_backup)) {
                        Text(stringResource(R.string.master_backup_hint), style = MaterialTheme.typography.bodySmall)
                        BigButton(stringResource(R.string.master_backup_export), enabled = !busy, primary = false) {
                            launchTask {
                                val zip = withContext(Dispatchers.IO) { master.exportBackup() }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, zip.name))
                            }
                        }
                        var confirmRestore by remember { mutableStateOf(false) }
                        BigButton(stringResource(R.string.master_backup_restore), enabled = !busy && !state.running, primary = false) {
                            confirmRestore = true
                        }
                        if (state.running) Text(stringResource(R.string.master_restore_stop_first), style = MaterialTheme.typography.bodySmall)
                        if (confirmRestore) {
                            AlertDialog(
                                onDismissRequest = { confirmRestore = false },
                                text = { Text(stringResource(R.string.master_restore_warning)) },
                                confirmButton = {
                                    TextButton({ confirmRestore = false; restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                                        Text(stringResource(R.string.confirm))
                                    }
                                },
                                dismissButton = { TextButton({ confirmRestore = false }) { Text(stringResource(R.string.cancel)) } },
                            )
                        }
                    }

                    SectionCard(stringResource(R.string.master_admin_reset)) {
                        var pwd by remember { mutableStateOf("") }
                        Text(stringResource(R.string.master_admin_reset_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(pwd, { pwd = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.new_password)) }, visualTransformation = PasswordVisualTransformation())
                        BigButton(stringResource(R.string.validate), enabled = !busy && pwd.length >= 8, primary = false) {
                            launchTask {
                                withContext(Dispatchers.IO) { master.resetAdminPassword(pwd) }
                                pwd = ""
                                message = context.getString(R.string.master_admin_reset_done)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupForm(busy: Boolean, onCreate: (password: String, company: String?, demo: Boolean) -> Unit) {
    var company by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var demo by remember { mutableStateOf(true) }
    SectionCard(stringResource(R.string.master_setup)) {
        Text(stringResource(R.string.master_setup_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.master_company)) })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.master_admin_password)) }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(confirm, { confirm = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.master_admin_password_confirm)) }, visualTransformation = PasswordVisualTransformation(),
            isError = confirm.isNotEmpty() && confirm != password)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(demo, { demo = it })
            Text(stringResource(R.string.master_demo), Modifier.weight(1f))
        }
        if (demo) Text(stringResource(R.string.master_demo_hint), style = MaterialTheme.typography.bodySmall)
        BigButton(stringResource(R.string.master_create), enabled = !busy && password.length >= 8 && password == confirm) {
            onCreate(password, company.trim().ifEmpty { null }, demo)
        }
    }
}
