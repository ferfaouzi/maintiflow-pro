package com.maintiflow.pro.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.api.TicketSummary
import com.maintiflow.pro.ui.components.Badge
import com.maintiflow.pro.ui.components.BigButton
import com.maintiflow.pro.ui.components.PriorityBadge
import com.maintiflow.pro.ui.components.StatusBadge
import com.maintiflow.pro.ui.components.sinceText
import com.maintiflow.pro.ui.theme.Orange
import com.maintiflow.pro.ui.theme.StatusStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val items: List<TicketSummary> = emptyList(),
    val loading: Boolean = false,
    val fromCache: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(private val repo: Repository) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state
    val pending = repo.observePending()

    fun load(mine: Boolean) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { repo.home(mine) }
            .onSuccess { _state.value = HomeState(it.value.items, false, it.fromCache) }
            .onFailure { _state.value = _state.value.copy(loading = false, error = repo.client.messageOf(it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: Repository,
    onNewTicket: () -> Unit,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAdmin: () -> Unit,
) {
    val vm: HomeViewModel = viewModel { HomeViewModel(repo) }
    val state by vm.state.collectAsState()
    val pending by vm.pending.collectAsState(initial = emptyList())
    val user by repo.session.user.collectAsState()
    val role = user?.role ?: ""
    val isChef = role == "CHEF_EQUIPE"
    var tab by rememberSaveable { mutableStateOf(0) }
    val mine = isChef && tab == 1 || role == "TECHNICIEN" && tab == 1

    // Rafraîchissement automatique toutes les 20 s : les nouvelles demandes apparaissent sans action.
    LaunchedEffect(mine) {
        while (true) {
            vm.load(mine)
            delay(20_000)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                    Text(user?.fullName.orEmpty() + " · " + (StatusStyle.roleLabel[role] ?: role),
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            actions = {
                IconButton(onClick = { vm.load(mine) }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                if (role == "ADMIN") IconButton(onClick = onAdmin) {
                    Icon(Icons.Default.AdminPanelSettings, stringResource(R.string.admin))
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.fromCache) Banner(stringResource(R.string.offline_banner), Orange)
            if (pending.isNotEmpty()) Banner(stringResource(R.string.pending_sync, pending.size), Color(0xFF6A1B9A))
            state.error?.let { Banner(it, MaterialTheme.colorScheme.error) }

            if (isChef) {
                BigButton("＋  " + stringResource(R.string.new_ticket), Modifier.padding(16.dp),
                    container = MaterialTheme.colorScheme.secondary, onClick = onNewTicket)
            }
            if (isChef || role == "TECHNICIEN") {
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.home_priority)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.home_mine)) })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.items.isEmpty() && !state.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.home_empty), Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(state.items, key = { it.id }) { TicketCard(it) { onOpen(it.id) } }
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(text, color = Color.White,
        modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun TicketCard(t: TicketSummary, onClick: () -> Unit) {
    val stripe = StatusStyle.priorityColor[t.priority] ?: Color.Gray
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Spacer(Modifier.width(8.dp).fillMaxHeight().background(stripe))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.number, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    StatusBadge(t.status)
                    PriorityBadge(t.priority)
                }
                Text("${t.machineCode} – ${t.machineName}", fontWeight = FontWeight.SemiBold)
                Text("${t.lineName} · ${t.categoryName}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.late) Badge(stringResource(R.string.late), Color(0xFFB71C1C))
                    if (t.productionStopped) Badge(stringResource(R.string.production_stopped), Color(0xFF37474F))
                    if (t.recurring) Badge(stringResource(R.string.recurring), Color(0xFF6A1B9A))
                }
                Text("il y a ${sinceText(t.declaredAt)}" + (t.assignedTo?.let { " · ${it.fullName}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
