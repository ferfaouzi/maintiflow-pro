package com.maintiflow.pro.ui.ticket

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.api.ActionRequest
import com.maintiflow.pro.data.api.TicketDetail
import com.maintiflow.pro.ui.components.BigButton
import com.maintiflow.pro.ui.components.LabelValue
import com.maintiflow.pro.ui.components.PriorityBadge
import com.maintiflow.pro.ui.components.SectionCard
import com.maintiflow.pro.ui.components.SignaturePad
import com.maintiflow.pro.ui.components.SignatureState
import com.maintiflow.pro.ui.components.StatusBadge
import com.maintiflow.pro.ui.components.formatDate
import com.maintiflow.pro.ui.components.formatDuration
import com.maintiflow.pro.ui.theme.StatusStyle
import java.io.File

private val COMMENT_REQUIRED = setOf("pause", "wait_part", "cancel", "reopen", "reject_production", "reject_maintenance")
private val SIGNATURE_ACTIONS = setOf("sign_maintenance", "validate_maintenance", "confirm_production")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TicketScreen(repo: Repository, id: String, onBack: () -> Unit) {
    val vm: TicketViewModel = viewModel(key = "ticket-$id") { TicketViewModel(repo, id) }
    val st by vm.state.collectAsState()
    val context = LocalContext.current
    val role = repo.session.user.collectAsState().value?.role
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingPhoto by remember { mutableStateOf<Pair<File, String>?>(null) }
    val imageLoader = remember { ImageLoader.Builder(context).okHttpClient(repo.client.http).build() }

    LaunchedEffect(role) {
        if (role == "TECHNICIEN" || role == "RESPONSABLE") vm.loadSimilar()
        if (role == "RESPONSABLE") vm.loadRefs()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingPhoto?.let { (f, kind) -> if (ok) vm.uploadPhoto(f, kind) else f.delete() }
        pendingPhoto = null
    }
    fun takePhoto(kind: String) {
        val f = repo.newPhotoFile()
        pendingPhoto = f to kind
        camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(st.ticket?.number ?: "…") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                if (st.ticket != null && !st.fromCache) {
                    IconButton(onClick = { vm.downloadPdf { openPdf(context, it) } }) {
                        Icon(Icons.Default.PictureAsPdf, stringResource(R.string.pdf))
                    }
                    IconButton(onClick = { vm.downloadPdf { printPdf(context, it) } }) {
                        Icon(Icons.Default.Print, stringResource(R.string.print))
                    }
                    IconButton(onClick = { vm.downloadPdf { sharePdf(context, it) } }) {
                        Icon(Icons.Default.Share, stringResource(R.string.share))
                    }
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val t = st.ticket
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).widthIn(max = 1000.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (st.fromCache) Text(stringResource(R.string.offline_banner), color = Color(0xFFF57C00))
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                if (t == null) return@Column

                Header(t)
                if (!st.fromCache) Actions(t, st.busy) { name ->
                    when {
                        name == "repair" -> dialog = "repair"
                        name in COMMENT_REQUIRED || name in SIGNATURE_ACTIONS || name in setOf("assign", "flag_recurring") ->
                            dialog = name
                        else -> vm.action(name)
                    }
                }
                if (t.locked) Text(stringResource(R.string.locked_notice), color = MaterialTheme.colorScheme.primary)

                Report(t) { d, r, c, o, s -> vm.saveReport(d, r, c, o, s) }
                Parts(t, onAdd = { dialog = "part" }, onDelete = vm::deletePart)
                Photos(t, repo, imageLoader, canChef = role == "CHEF_EQUIPE", onTake = ::takePhoto)
                Times(t)
                Signatures(t)
                st.similar?.takeIf { it.items.isNotEmpty() && !t.locked }?.let { sim ->
                    SectionCard(stringResource(R.string.section_similar)) {
                        Text(sim.disclaimer, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8D6E00))
                        sim.items.forEach { s ->
                            Text("${s.number} · ${s.machine} · ${s.category}", fontWeight = FontWeight.SemiBold)
                            Text("Cause : ${s.rootCause ?: "—"}\nAction : ${s.correctiveAction ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                History(t)
            }
        }
    }

    val t = st.ticket ?: return
    when (val d = dialog) {
        null -> Unit
        "part" -> PartDialog(onDismiss = { dialog = null }) { r, des, q -> vm.addPart(r, des, q); dialog = null }
        "repair" -> CommentDialog(
            title = StatusStyle.actionLabel["repair"]!!,
            hint = "Vérifiez le rapport technique (diagnostic, actions, état final) avant de terminer.",
            required = false, onDismiss = { dialog = null },
        ) { c -> vm.action("repair", ActionRequest(comment = c)); dialog = null }
        "assign" -> AssignDialog(st.refs, t, onDismiss = { dialog = null }) { techId, prio ->
            vm.action("assign", ActionRequest(technicianId = techId, priority = prio)); dialog = null
        }
        in SIGNATURE_ACTIONS -> SignatureDialog(StatusStyle.actionLabel[d] ?: d, onDismiss = { dialog = null }) { png, c ->
            vm.action(d, ActionRequest(signaturePngBase64 = png, comment = c)); dialog = null
        }
        else -> CommentDialog(StatusStyle.actionLabel[d] ?: d, null, required = d in COMMENT_REQUIRED,
            onDismiss = { dialog = null }) { c -> vm.action(d, ActionRequest(comment = c)); dialog = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(t: TicketDetail) {
    SectionCard(stringResource(R.string.section_declaration)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(t.status); PriorityBadge(t.priority)
            if (t.recurring) com.maintiflow.pro.ui.components.Badge(stringResource(R.string.recurring), Color(0xFF6A1B9A))
            if (t.paused) com.maintiflow.pro.ui.components.Badge("SUSPENDU", Color(0xFF5D4037))
        }
        Text("${t.machineCode} – ${t.machineName}", style = MaterialTheme.typography.titleMedium)
        LabelValue("Ligne", t.lineName)
        LabelValue("Type de panne", t.categoryName)
        LabelValue("Problème", t.description)
        LabelValue("Déclaré le", formatDate(t.declaredAt))
        LabelValue(stringResource(R.string.reported_by), t.reportedBy.fullName)
        LabelValue(stringResource(R.string.technician), t.assignedTo?.fullName)
        LabelValue("Arrêt production", if (t.productionStopped)
            "${formatDate(t.stopStartAt)} → ${t.stopEndAt?.let { formatDate(it) } ?: "en cours"}" else "Non")
        t.cancelReason?.let { LabelValue("Motif d'annulation", it) }
        if (t.revision > 1) LabelValue("Révision", t.revision.toString())
    }
}

@Composable
private fun Actions(t: TicketDetail, busy: Boolean, onAction: (String) -> Unit) {
    val (primary, secondary) = t.allowedActions.partition { it in StatusStyle.primaryActions }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        primary.forEach { a ->
            BigButton(StatusStyle.actionLabel[a] ?: a, enabled = !busy,
                container = if (a == "confirm_production") Color(0xFF2E7D32) else null) { onAction(a) }
        }
        secondary.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    OutlinedButton(onClick = { onAction(a) }, Modifier.weight(1f).heightIn(min = 56.dp), enabled = !busy) {
                        Text(StatusStyle.actionLabel[a] ?: a)
                    }
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Report(t: TicketDetail, onSave: (String, String, String, String, String?) -> Unit) {
    val editable = "diagnosis" in t.editableFields
    if (!editable && t.diagnosis == null && t.correctiveAction == null) return
    var diagnosis by remember(t.version) { mutableStateOf(t.diagnosis ?: "") }
    var cause by remember(t.version) { mutableStateOf(t.rootCause ?: "") }
    var action by remember(t.version) { mutableStateOf(t.correctiveAction ?: "") }
    var obs by remember(t.version) { mutableStateOf(t.observations ?: "") }
    var state by remember(t.version) { mutableStateOf(t.machineFinalState) }
    SectionCard(stringResource(R.string.section_report)) {
        if (!editable) {
            LabelValue(stringResource(R.string.diagnosis), t.diagnosis)
            LabelValue(stringResource(R.string.root_cause), t.rootCause)
            LabelValue(stringResource(R.string.corrective_action), t.correctiveAction)
            LabelValue(stringResource(R.string.observations), t.observations)
            LabelValue(stringResource(R.string.machine_final_state),
                StatusStyle.machineStates.firstOrNull { it.first == t.machineFinalState }?.second)
            return@SectionCard
        }
        OutlinedTextField(diagnosis, { diagnosis = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.diagnosis) + " *") })
        OutlinedTextField(cause, { cause = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.root_cause)) })
        OutlinedTextField(action, { action = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.corrective_action) + " *") })
        OutlinedTextField(obs, { obs = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.observations)) })
        Text(stringResource(R.string.machine_final_state) + " *")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusStyle.machineStates.forEach { (k, v) ->
                FilterChip(state == k, { state = k }, label = { Text(v) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        BigButton(stringResource(R.string.save), primary = false) { onSave(diagnosis, cause, action, obs, state) }
    }
}

@Composable
private fun Parts(t: TicketDetail, onAdd: () -> Unit, onDelete: (Int) -> Unit) {
    val editable = "corrective_action" in t.editableFields
    if (!editable && t.parts.isEmpty()) return
    SectionCard(stringResource(R.string.section_parts)) {
        t.parts.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.reference} – ${p.designation} × ${p.quantity} ${p.unit}", Modifier.weight(1f))
                if (editable) IconButton(onClick = { onDelete(p.id) }) { Icon(Icons.Default.Delete, null) }
            }
        }
        if (editable) OutlinedButton(onAdd, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.add_part))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Photos(t: TicketDetail, repo: Repository, loader: ImageLoader, canChef: Boolean, onTake: (String) -> Unit) {
    val techCan = "diagnosis" in t.editableFields
    val chefCan = canChef && !t.locked
    if (t.attachments.isEmpty() && !techCan && !chefCan) return
    SectionCard("Photos (${t.attachments.size})") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            t.attachments.forEach { a ->
                Column {
                    AsyncImage(model = repo.session.serverUrl + "attachments/${a.id}", contentDescription = a.kind,
                        imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp))
                    Text(a.kind, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chefCan) OutlinedButton({ onTake("PROBLEME") }, Modifier.weight(1f)) { Text("+ Photo") }
            if (techCan) {
                OutlinedButton({ onTake("AVANT") }, Modifier.weight(1f)) { Text("+ Photo AVANT") }
                OutlinedButton({ onTake("APRES") }, Modifier.weight(1f)) { Text("+ Photo APRÈS") }
            }
        }
    }
}

@Composable
private fun Times(t: TicketDetail) {
    val d = t.durations
    SectionCard(stringResource(R.string.section_times)) {
        LabelValue("Délai de prise en charge", formatDuration(d.response))
        LabelValue("Délai d'arrivée", formatDuration(d.arrival))
        LabelValue("Intervention effective", formatDuration(d.activeMaintenance))
        LabelValue("Attente pièces", formatDuration(d.waitingParts))
        LabelValue("Suspensions", formatDuration(d.paused))
        LabelValue("Arrêt de production", formatDuration(d.productionDowntime))
        LabelValue("Durée totale", formatDuration(d.total) + if (d.running) " (en cours)" else "")
    }
}

@Composable
private fun Signatures(t: TicketDetail) {
    val active = t.signatures.filter { !it.superseded && it.revision == t.revision }
    if (active.isEmpty()) return
    SectionCard(stringResource(R.string.section_signatures)) {
        active.forEach { s -> LabelValue(s.role, "${s.userName} · ${formatDate(s.signedAt)}") }
        Text("Empreinte du contenu : ${t.contentSha256.take(16)}…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun History(t: TicketDetail) {
    SectionCard(stringResource(R.string.section_history)) {
        t.events.asReversed().forEach { e ->
            Column {
                Text("${formatDate(e.at)} · ${StatusStyle.eventLabel[e.type] ?: e.type}" +
                    (e.toStatus?.let { " → ${StatusStyle.label[it]}" } ?: ""), fontWeight = FontWeight.SemiBold)
                Text((e.userName ?: "système") + (e.comment?.let { " : $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ dialogues
@Composable
private fun CommentDialog(title: String, hint: String?, required: Boolean, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let { Text(it) }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    label = { Text(stringResource(if (required) R.string.comment_required else R.string.comment)) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifEmpty { null }) }, enabled = !required || text.trim().length >= 5) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SignatureDialog(title: String, onDismiss: () -> Unit, onConfirm: (String?, String?) -> Unit) {
    val sig = remember { SignatureState() }
    var w by remember { mutableFloatStateOf(1f) }
    var h by remember { mutableFloatStateOf(1f) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sign_here))
                SignaturePad(sig) { sw, sh -> w = sw; h = sh }
                OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.comment)) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sig.toPngBase64(srcW = w, srcH = h), comment.trim().ifEmpty { null }) },
                enabled = !sig.isEmpty) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PartDialog(onDismiss: () -> Unit, onConfirm: (String, String, Double) -> Unit) {
    var ref by remember { mutableStateOf("") }
    var des by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    val q = qty.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_part)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ref, { ref = it }, label = { Text(stringResource(R.string.reference)) }, singleLine = true)
                OutlinedTextField(des, { des = it }, label = { Text(stringResource(R.string.designation)) })
                OutlinedTextField(qty, { qty = it }, label = { Text(stringResource(R.string.quantity)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ref, des, q!!) }, enabled = ref.isNotBlank() && des.isNotBlank() && q != null && q > 0) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignDialog(refs: com.maintiflow.pro.data.api.Referentials?, t: TicketDetail, onDismiss: () -> Unit,
                         onConfirm: (Int, String?) -> Unit) {
    var tech by remember { mutableStateOf(t.assignedTo?.id) }
    var prio by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(StatusStyle.actionLabel["assign"]!!) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    refs?.technicians?.forEach { u -> FilterChip(tech == u.id, { tech = u.id }, label = { Text(u.fullName) }) }
                }
                Text(stringResource(R.string.priority) + " : " + (prio ?: t.priority))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusStyle.priorities.forEach { p -> FilterChip(prio == p, { prio = p }, label = { Text(p) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(tech!!, prio) }, enabled = tech != null) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ PDF : ouvrir, imprimer, partager
private fun uriOf(context: Context, f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)

private fun openPdf(context: Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(context, f), "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(i, f.name)) }
}

private fun sharePdf(context: Context, f: File) {
    val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uriOf(context, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(i, f.name))
}

private fun printPdf(context: Context, f: File) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(f.nameWithoutExtension, PdfPrintAdapter(f), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
}
