package com.yubing.cloudlink.nav

import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yubing.cloudlink.data.local.TokenStore
import com.yubing.cloudlink.ui.home.HomeScreen
import com.yubing.cloudlink.ui.login.LoginScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AuthState @Inject constructor(
    tokenStore: TokenStore,
) : ViewModel() {
    val isLoggedIn: StateFlow<Boolean?> = tokenStore.accessToken
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    authState: AuthState = hiltViewModel(),
) {
    val loggedIn by authState.isLoggedIn.collectAsState()

    // Navigate to home when logged in, login when not
    LaunchedEffect(loggedIn) {
        when (loggedIn) {
            true -> {
                navController.navigate("home") {
                    popUpTo("login") { inclusive = true }
                }
            }
            false -> {
                navController.navigate("login") {
                    popUpTo(0) { inclusive = true }
                }
            }
            null -> { /* initial state, wait */ }
        }
    }

    NavHost(navController = navController, startDestination = "loading") {
        composable("loading") { /* blank while we determine auth state */ }
        composable("login") {
            LoginScreen(onLoginSuccess = { /* nav effect handles it */ })
        }
        composable("home") {
            HomeScreen(
                onLogout = { /* nav effect handles it */ },
                onSettings = { navController.navigate("settings") },
                onDeviceClick = { device ->
                    navController.navigate("device/${device.haDeviceId}")
                },
            )
        }
        composable(
            route = "device/{haDeviceId}",
            arguments = listOf(androidx.navigation.navArgument("haDeviceId") {
                type = androidx.navigation.NavType.StringType
            }),
        ) {
            com.yubing.cloudlink.ui.detail.DeviceDetailScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            com.yubing.cloudlink.ui.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
