package com.hackathon.recall.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hackathon.recall.R
import com.hackathon.recall.data.effectiveType
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val HOME = "home"
    const val VAULT = "vault"
    const val BENCHMARK = "benchmark"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    fun results(q: String) = "results?q=${Uri.encode(q)}"
    fun checklist(template: String, pin: Long? = null) = "checklist/$template" + (pin?.let { "?pin=$it" } ?: "")
    fun doc(id: Long) = "doc/$id"
    fun scan(expected: String? = null) = "scan" + (expected?.let { "?expected=$it" } ?: "")
    fun vaultCategory(category: String) = "vault?category=$category"
}

/** Top level: wait for the encrypted vault to open, then the biometric gate, then first-run onboarding, then the app. */
@Composable
fun RecallRoot(renewalDocId: StateFlow<Long?>, onRenewalHandled: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val ready by container.ready.collectAsState()
    val unlocked by container.session.unlocked.collectAsState()
    var onboarded by remember { mutableStateOf(Onboarding.completed(context)) }

    when {
        !ready -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(R.string.opening_vault))
        }
        !unlocked -> LockScreen(onUnlocked = container.session::unlock)
        !onboarded -> OnboardingScreen(onDone = { onboarded = true })
        else -> {
            val nav = rememberNavController()
            RecallNav(nav)
            val pending by renewalDocId.collectAsState()
            LaunchedEffect(pending) {
                val id = pending ?: return@LaunchedEffect
                container.repository.byId(id)?.let { doc ->
                    nav.navigate(Routes.checklist(container.templates.renewalTemplateFor(doc.effectiveType()).id, id))
                }
                onRenewalHandled()
            }
        }
    }
}

@Composable
fun RecallNav(nav: NavHostController) {
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(
            "results?q={q}",
            arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
        ) { ResultsScreen(nav, it.arguments?.getString("q").orEmpty()) }
        composable(
            "checklist/{template}?pin={pin}",
            arguments = listOf(
                navArgument("template") { type = NavType.StringType },
                navArgument("pin") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { ChecklistScreen(nav, it.arguments?.getString("template").orEmpty(), it.arguments?.getLong("pin")?.takeIf { p -> p > 0 }) }
        composable("doc/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            DocDetailScreen(nav, it.arguments?.getLong("id") ?: -1)
        }
        composable(Routes.VAULT) { VaultScreen(nav, initialCategory = null) }
        composable(
            "vault?category={category}",
            arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { VaultScreen(nav, initialCategory = it.arguments?.getString("category")) }
        composable(
            "scan?expected={expected}",
            arguments = listOf(navArgument("expected") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { CameraScanScreen(nav, it.arguments?.getString("expected")) }
        composable(Routes.BENCHMARK) { BenchmarkScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.MODELS) { ModelsScreen(nav) }
    }
}
