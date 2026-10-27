package com.maintiflow.pro.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.api.TicketPage
import com.maintiflow.pro.ui.home.TicketCard
import com.maintiflow.pro.ui.theme.StatusStyle
import kotlinx.coroutines.launch

/** Recherche : n° de bon, machine, texte + filtre d'état. Les filtres avancés (ligne, technicien, dates)
 *  sont exposés par l'API et seront ajoutés à l'écran en phase 3 (tableau de bord). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(repo: Repository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    var page by androidx.compose.runtime.remember { mutableStateOf<TicketPage?>(null) }
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }

    fun doSearch() {
        busy = true
        error = null
        scope.launch {
            runCatching { repo.search(q, status?.let { listOf(it) }, openOnly) }
                .onSuccess { page = it }.onFailure { error = repo.client.messageOf(it) }
            busy = false
        }
    }
    LaunchedEffect(status, openOnly) { doSearch() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.search)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = { IconButton(onClick = { doSearch() }) { Icon(Icons.Default.Search, null) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(openOnly, { openOnly = !openOnly }, label = { Text(stringResource(R.string.open_only)) })
                FilterChip(status == null, { status = null }, label = { Text(stringResource(R.string.all_statuses)) })
                StatusStyle.label.forEach { (k, v) -> FilterChip(status == k, { status = k }, label = { Text(v) }) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            page?.let { Text(stringResource(R.string.results, it.total)) }
            LazyVerticalGrid(GridCells.Adaptive(340.dp), contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(page?.items.orEmpty(), key = { it.id }) { TicketCard(it) { onOpen(it.id) } }
            }
        }
    }
}
