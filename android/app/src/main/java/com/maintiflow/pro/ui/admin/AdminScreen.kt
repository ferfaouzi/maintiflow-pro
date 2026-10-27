package com.maintiflow.pro.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.api.CategoryDto
import com.maintiflow.pro.data.api.CategoryRequest
import com.maintiflow.pro.data.api.LineDto
import com.maintiflow.pro.data.api.LineRequest
import com.maintiflow.pro.data.api.MachineDto
import com.maintiflow.pro.data.api.MachineRequest
import com.maintiflow.pro.data.api.UserCreateRequest
import com.maintiflow.pro.data.api.UserDto
import com.maintiflow.pro.data.api.UserUpdateRequest
import com.maintiflow.pro.ui.components.BigButton
import com.maintiflow.pro.ui.components.SectionCard
import com.maintiflow.pro.ui.theme.StatusStyle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Administration du site : utilisateurs, lignes, machines, catégories de pannes, paramètres.
 *  Le journal d'audit et la sauvegarde sont gérés côté serveur (voir docs/BACKUP_RESTORE.md). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(repo: Repository, onBack: () -> Unit) {
    val api = repo.client.api
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var users by remember { mutableStateOf(emptyList<UserDto>()) }
    var lines by remember { mutableStateOf(emptyList<LineDto>()) }
    var machines by remember { mutableStateOf(emptyList<MachineDto>()) }
    var categories by remember { mutableStateOf(emptyList<CategoryDto>()) }
    var settings by remember { mutableStateOf(emptyMap<String, JsonElement>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }

    fun guarded(block: suspend () -> Unit) = scope.launch {
        error = null
        runCatching { block() }.onFailure { error = repo.client.messageOf(it) }
    }
    fun reload() = guarded {
        users = api.users(); lines = api.lines(); machines = api.machines(); categories = api.categories()
        settings = api.settings().values
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.admin)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab) {
                listOf("Utilisateurs", "Lignes & machines", "Pannes", "Paramètres").forEachIndexed { i, s ->
                    Tab(tab == i, { tab = i }, text = { Text(s) })
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (tab) {
                    0 -> {
                        BigButton("+ Nouvel utilisateur") { dialog = "user" }
                        users.forEach { u ->
                            SectionCard("${u.fullName} (${u.username})") {
                                Text(StatusStyle.roleLabel[u.role] ?: u.role)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Actif", Modifier.weight(1f))
                                    Switch(u.active, { v -> guarded { api.updateUser(u.id, UserUpdateRequest(active = v)); reload() } })
                                }
                                TextButton(onClick = { dialog = "reset:${u.id}" }) { Text("Réinitialiser le mot de passe") }
                            }
                        }
                    }
                    1 -> {
                        BigButton("+ Nouvelle ligne") { dialog = "line" }
                        BigButton("+ Nouvelle machine", primary = false, enabled = lines.isNotEmpty()) { dialog = "machine" }
                        lines.forEach { l ->
                            SectionCard("${l.code} – ${l.name}") {
                                machines.filter { it.lineId == l.id }.forEach { m ->
                                    Text("${m.code} – ${m.name}" + if (!m.active) " (inactive)" else "")
                                }
                            }
                        }
                    }
                    2 -> {
                        BigButton("+ Nouvelle catégorie de panne") { dialog = "category" }
                        categories.forEach { c -> Text("${c.code} – ${c.name} · ${c.defaultPriority}", fontWeight = FontWeight.Medium) }
                    }
                    3 -> {
                        val require = settings["require_manager_validation"]?.jsonPrimitive?.booleanOrNull ?: false
                        SectionCard("Règles de validation") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Validation par le Responsable Maintenance obligatoire avant la confirmation Production",
                                    Modifier.weight(1f))
                                Switch(require, { v ->
                                    guarded { settings = api.putSettings(mapOf("require_manager_validation" to JsonPrimitive(v))).values }
                                })
                            }
                        }
                        SectionCard("Autres paramètres (lecture)") {
                            settings.filterKeys { it != "require_manager_validation" }.forEach { (k, v) -> Text("$k : $v") }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        "user" -> FormDialog("Nouvel utilisateur", listOf("Identifiant", "Nom complet", "Mot de passe initial"),
            choices = StatusStyle.roleLabel.toList(), onDismiss = { dialog = null }) { v, role ->
            guarded { api.createUser(UserCreateRequest(v[0], v[1], role!!, v[2])); reload() }; dialog = null
        }
        "line" -> FormDialog("Nouvelle ligne", listOf("Code", "Nom"), onDismiss = { dialog = null }) { v, _ ->
            guarded { api.createLine(LineRequest(v[0], v[1])); reload() }; dialog = null
        }
        "machine" -> FormDialog("Nouvelle machine", listOf("Code", "Nom"),
            choices = lines.map { it.id.toString() to it.name }, onDismiss = { dialog = null }) { v, line ->
            guarded { api.createMachine(MachineRequest(line!!.toInt(), v[0], v[1])); reload() }; dialog = null
        }
        "category" -> FormDialog("Nouvelle catégorie", listOf("Code", "Nom"),
            choices = StatusStyle.priorities.map { it to it }, onDismiss = { dialog = null }) { v, prio ->
            guarded { api.createCategory(CategoryRequest(v[0], v[1], prio!!)); reload() }; dialog = null
        }
        else -> if (d.startsWith("reset:")) {
            FormDialog("Nouveau mot de passe provisoire", listOf("Mot de passe"), onDismiss = { dialog = null }) { v, _ ->
                guarded { api.updateUser(d.removePrefix("reset:").toInt(), UserUpdateRequest(resetPassword = v[0])) }
                dialog = null
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormDialog(
    title: String, fields: List<String>, choices: List<Pair<String, String>> = emptyList(),
    onDismiss: () -> Unit, onConfirm: (List<String>, String?) -> Unit,
) {
    val values = remember { fields.map { mutableStateOf("") } }
    var choice by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, f ->
                    OutlinedTextField(values[i].value, { values[i].value = it }, Modifier.fillMaxWidth(),
                        label = { Text(f) }, singleLine = true)
                }
                if (choices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { (k, v) -> FilterChip(choice == k, { choice = k }, label = { Text(v) }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values.map { it.value.trim() }, choice) },
                enabled = values.all { it.value.isNotBlank() } && (choices.isEmpty() || choice != null),
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
