package com.maintiflow.pro

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.sync.SyncWorker
import com.maintiflow.pro.ui.admin.AdminScreen
import com.maintiflow.pro.ui.home.HomeScreen
import com.maintiflow.pro.ui.login.ChangePasswordScreen
import com.maintiflow.pro.ui.login.LoginScreen
import com.maintiflow.pro.ui.search.SearchScreen
import com.maintiflow.pro.ui.settings.SettingsScreen
import com.maintiflow.pro.ui.theme.MaintiFlowTheme
import com.maintiflow.pro.ui.ticket.NewTicketScreen
import com.maintiflow.pro.ui.ticket.TicketScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as MaintiFlowApp).container.repository
        SyncWorker.enqueue(this)
        setContent {
            val dark by repo.session.darkMode.collectAsState()
            MaintiFlowTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) { Root(repo) }
            }
        }
    }
}

@Composable
private fun Root(repo: Repository) {
    val user by repo.session.user.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        repo.client.sessionExpired.collect {
            Toast.makeText(context, R.string.error_session, Toast.LENGTH_LONG).show()
            repo.logout()
        }
    }
    val u = user
    when {
        u == null -> LoginScreen(repo)
        u.mustChangePassword -> ChangePasswordScreen(repo)
        else -> AppNav(repo)
    }
}

@Composable
private fun AppNav(repo: Repository) {
    val nav = rememberNavController()
    val context = LocalContext.current
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repo,
                onNewTicket = { nav.navigate("new") },
                onOpen = { nav.navigate("ticket/$it") },
                onSearch = { nav.navigate("search") },
                onSettings = { nav.navigate("settings") },
                onAdmin = { nav.navigate("admin") },
            )
        }
        composable("new") {
            NewTicketScreen(repo, onBack = { nav.popBackStack() }) { id, message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                nav.popBackStack()
                if (id != null) nav.navigate("ticket/$id")
            }
        }
        composable("ticket/{id}") { entry ->
            TicketScreen(repo, entry.arguments?.getString("id")!!, onBack = { nav.popBackStack() })
        }
        composable("search") { SearchScreen(repo, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("ticket/$it") }) }
        composable("settings") { SettingsScreen(repo, onBack = { nav.popBackStack() }) }
        composable("admin") { AdminScreen(repo, onBack = { nav.popBackStack() }) }
    }
}
