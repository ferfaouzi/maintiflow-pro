package com.maintiflow.pro.ui.ticket

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.maintiflow.pro.R
import com.maintiflow.pro.data.CreateResult
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.api.Referentials
import com.maintiflow.pro.sync.SyncWorker
import com.maintiflow.pro.ui.components.BigButton
import com.maintiflow.pro.ui.theme.StatusStyle
import kotlinx.coroutines.launch
import java.io.File

private val QUICK_PHRASES = listOf("Machine arrêtée", "Bruit anormal", "Fuite", "Alarme / défaut affiché",
    "Pièces non conformes", "Blocage / bourrage", "Problème de sécurité")

/**
 * Formulaire court du chef d'équipe : ligne → machine → type de panne → description → ENVOYER.
 * Fonctionne hors connexion (référentiels en cache, envoi différé sans doublon).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTicketScreen(repo: Repository, onBack: () -> Unit, onCreated: (String?, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refs by remember { mutableStateOf<Referentials?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lineId by remember { mutableStateOf<Int?>(null) }
    var machineId by remember { mutableStateOf<Int?>(null) }
    var categoryId by remember { mutableStateOf<Int?>(null) }
    var description by remember { mutableStateOf("") }
    var stopped by remember { mutableStateOf(true) }
    var priority by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val photos = remember { mutableStateListOf<File>() }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val sentText = stringResource(R.string.created, "%s")
    val offlineText = stringResource(R.string.saved_offline)

    LaunchedEffect(Unit) {
        runCatching { repo.referentials() }
            .onSuccess { refs = it.value; if (it.value.lines.size == 1) lineId = it.value.lines.first().id }
            .onFailure { error = repo.client.messageOf(it) }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        if (ok && f != null) scope.launch { photos.add(repo.compressPhoto(f)) } else f?.delete()
        pendingPhoto = null
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.new_ticket)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                .padding(16.dp).widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val r = refs ?: return@Column

            Step(stringResource(R.string.step_line))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.lines.forEach { l ->
                    FilterChip(lineId == l.id, { lineId = l.id; machineId = null }, label = { Text(l.name) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            if (lineId != null) {
                Step(stringResource(R.string.step_machine))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.machines.filter { it.lineId == lineId }.forEach { m ->
                        FilterChip(machineId == m.id, { machineId = m.id }, label = { Text("${m.code}\n${m.name}") },
                            modifier = Modifier.heightIn(min = 56.dp))
                    }
                }
            }
            if (machineId != null) {
                Step(stringResource(R.string.step_category))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.categories.forEach { c ->
                        FilterChip(categoryId == c.id, { categoryId = c.id }, label = { Text(c.name) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            if (categoryId != null) {
                Step(stringResource(R.string.step_description))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_PHRASES.forEach { p ->
                        AssistChip(onClick = { description = if (description.isBlank()) p else "$description, ${p.lowercase()}" },
                            label = { Text(p) })
                    }
                }
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    placeholder = { Text(stringResource(R.string.description_hint)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.production_is_stopped), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Switch(stopped, { stopped = it })
                }
                Text(stringResource(R.string.priority) + " : " + (priority ?: "auto (selon le type de panne)"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusStyle.priorities.forEach { p ->
                        FilterChip(priority == p, { priority = if (priority == p) null else p }, label = { Text(p) })
                    }
                }
                OutlinedButton(onClick = {
                    val f = repo.newPhotoFile()
                    pendingPhoto = f
                    val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    camera.launch(uri)
                }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Text("  " + stringResource(R.string.add_photo) +
                        if (photos.isNotEmpty()) " (" + stringResource(R.string.photos_count, photos.size) + ")" else "")
                }
                BigButton(stringResource(R.string.send), enabled = !busy && description.trim().length >= 3) {
                    busy = true
                    val machine = r.machines.first { it.id == machineId }
                    scope.launch {
                        runCatching {
                            repo.createTicket(machineId!!, categoryId!!, description, stopped, priority, photos.toList(),
                                "${machine.code} – $description")
                        }.onSuccess { res ->
                            when (res) {
                                is CreateResult.Sent -> onCreated(res.ticket.id, sentText.format(res.ticket.number))
                                CreateResult.SavedOffline -> {
                                    SyncWorker.enqueue(context)
                                    onCreated(null, offlineText)
                                }
                            }
                        }.onFailure { error = repo.client.messageOf(it); busy = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
