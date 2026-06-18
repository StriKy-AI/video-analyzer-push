package com.example.videoanalyzer.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.videoanalyzer.VideoAnalyzerApp
import com.example.videoanalyzer.ui.home.HomeScreen
import com.example.videoanalyzer.ui.settings.SettingsScreen
import com.example.videoanalyzer.ui.setup.SetupScreen
import kotlinx.coroutines.flow.first

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/**
 * Reads persistent config once, decides where to land, and renders a small
 * spinner while the DataStore is being read on cold start.
 *
 * On every recomposition after that it reacts to preference changes — e.g.
 * if the user wipes everything in Settings, we auto-route back to Setup.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as VideoAnalyzerApp

    var hasBootstrapped by remember { mutableStateOf(false) }
    var initialIsConfigured by remember { mutableStateOf(false) }

    // One-shot read of persisted config on first composition.
    LaunchedEffect(Unit) {
        val baseUrl = app.preferences.baseUrl.first()
        val apiKey = app.preferences.apiKey.first()
        val selectedModel = app.preferences.selectedModel.first()
        initialIsConfigured =
            baseUrl.isNotBlank() && apiKey.isNotBlank() && selectedModel.isNotBlank()
        hasBootstrapped = true
    }

    // React to live changes — if the user wipes data while the app is running,
    // send them back to Setup.
    val baseUrlLive by app.preferences.baseUrl.collectAsStateWithLifecycle(initialValue = "")
    val apiKeyLive by app.preferences.apiKey.collectAsStateWithLifecycle(initialValue = "")
    val modelLive by app.preferences.selectedModel.collectAsStateWithLifecycle(initialValue = "")
    val isConfiguredLive =
        baseUrlLive.isNotBlank() && apiKeyLive.isNotBlank() && modelLive.isNotBlank()

    if (!hasBootstrapped) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (initialIsConfigured) Routes.HOME else Routes.SETUP,
    ) {
        composable(Routes.SETUP) {
            // If the user is already configured but somehow ended up on Setup,
            // bounce them to Home automatically.
            LaunchedEffect(isConfiguredLive) {
                if (isConfiguredLive) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            }
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
                onSkipToHome = {
                    navController.navigate(Routes.HOME)
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onResetSetup = {
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onWipeAndGoToSetup = {
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}