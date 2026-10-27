package com.maintiflow.pro.ui.ticket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.api.ActionRequest
import com.maintiflow.pro.data.api.PartRequest
import com.maintiflow.pro.data.api.PatchTicketRequest
import com.maintiflow.pro.data.api.Referentials
import com.maintiflow.pro.data.api.SimilarResponse
import com.maintiflow.pro.data.api.TicketDetail
import com.maintiflow.pro.data.api.httpCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class TicketState(
    val ticket: TicketDetail? = null,
    val fromCache: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val similar: SimilarResponse? = null,
    val refs: Referentials? = null,
)

class TicketViewModel(private val repo: Repository, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(TicketState())
    val state: StateFlow<TicketState> = _state

    init {
        reload()
    }

    fun reload() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true, error = null)
        runCatching { repo.ticket(id) }
            .onSuccess { _state.value = _state.value.copy(ticket = it.value, fromCache = it.fromCache, busy = false) }
            .onFailure { _state.value = _state.value.copy(busy = false, error = repo.client.messageOf(it)) }
    }

    fun loadSimilar() = viewModelScope.launch {
        runCatching { repo.similar(id) }.onSuccess { _state.value = _state.value.copy(similar = it) }
    }

    fun loadRefs() = viewModelScope.launch {
        runCatching { repo.referentials() }.onSuccess { _state.value = _state.value.copy(refs = it.value) }
    }

    private fun mutate(block: suspend (TicketDetail) -> TicketDetail) {
        val t = _state.value.ticket ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            runCatching { block(t) }
                .onSuccess { _state.value = _state.value.copy(ticket = it, busy = false, fromCache = false) }
                .onFailure { e ->
                    val msg = repo.client.messageOf(e)
                    val conflict = e.httpCode() == 409 && msg.contains("modifié")
                    _state.value = _state.value.copy(busy = false, error = msg)
                    if (conflict) reload()  // verrou optimiste : on recharge la version serveur
                }
        }
    }

    fun action(name: String, req: ActionRequest = ActionRequest()) = mutate { repo.action(it, name, req) }

    fun saveReport(diagnosis: String, rootCause: String, corrective: String, observations: String, state: String?) =
        mutate {
            repo.patch(it, PatchTicketRequest(
                version = it.version,
                diagnosis = diagnosis.takeIf { s -> s != (it.diagnosis ?: "") },
                rootCause = rootCause.takeIf { s -> s != (it.rootCause ?: "") },
                correctiveAction = corrective.takeIf { s -> s != (it.correctiveAction ?: "") },
                observations = observations.takeIf { s -> s != (it.observations ?: "") },
                machineFinalState = state.takeIf { s -> s != it.machineFinalState },
            ))
        }

    fun addPart(reference: String, designation: String, quantity: Double) =
        mutate { repo.addPart(it, PartRequest(reference.trim(), designation.trim(), quantity)) }

    fun deletePart(partId: Int) = mutate { repo.deletePart(it, partId) }

    fun uploadPhoto(file: File, kind: String) = mutate { repo.uploadPhoto(it.id, repo.compressPhoto(file), kind) }

    fun downloadPdf(onReady: (File) -> Unit) {
        val t = _state.value.ticket ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { repo.downloadPdf(t) }
                .onSuccess { _state.value = _state.value.copy(busy = false); onReady(it) }
                .onFailure { _state.value = _state.value.copy(busy = false, error = repo.client.messageOf(it)) }
        }
    }
}
