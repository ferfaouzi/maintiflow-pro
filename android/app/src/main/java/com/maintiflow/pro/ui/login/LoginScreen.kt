package com.maintiflow.pro.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.maintiflow.pro.R
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.ui.components.BigButton
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(repo: Repository, onMaster: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(repo.session.serverUrl.removeSuffix("/api/v1/")) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 32.dp))
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleMedium)
        val fieldMod = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        OutlinedTextField(server, { server = it }, fieldMod, label = { Text(stringResource(R.string.server_url)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(username, { username = it }, fieldMod, label = { Text(stringResource(R.string.username)) },
            singleLine = true)
        OutlinedTextField(password, { password = it }, fieldMod, label = { Text(stringResource(R.string.password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BigButton(stringResource(R.string.login), fieldMod,
            enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            busy = true
            error = null
            scope.launch {
                runCatching { repo.login(server, username, password) }
                    .onFailure { error = repo.client.messageOf(it) }
                busy = false
            }
        }
        BigButton(stringResource(R.string.master_open), fieldMod, primary = false, onClick = onMaster)
    }
}

@Composable
fun ChangePasswordScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.change_password_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(current, { current = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.current_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(new, { new = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.new_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BigButton(stringResource(R.string.validate), enabled = !busy && new.length >= 8) {
            busy = true
            scope.launch {
                runCatching { repo.changePassword(current, new) }.onFailure { error = repo.client.messageOf(it) }
                busy = false
            }
        }
        BigButton(stringResource(R.string.logout), primary = false) { scope.launch { repo.logout() } }
    }
}
