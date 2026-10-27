package com.maintiflow.pro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maintiflow.pro.BuildConfig
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.ui.components.BigButton
import com.maintiflow.pro.ui.theme.StatusStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val user by repo.session.user.collectAsState()
    val dark by repo.session.darkMode.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.connected_as, user?.fullName ?: "", user?.role?.let { StatusStyle.roleLabel[it] } ?: ""))
            Text(repo.session.serverUrl, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dark_mode), Modifier.weight(1f))
                Switch(dark, { repo.session.setDarkMode(it) })
            }
            Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            BigButton(stringResource(R.string.logout), primary = false) { scope.launch { repo.logout() } }
        }
    }
}
