package com.yourname.cloudlink.nav

import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yourname.cloudlink.data.local.TokenStore
import com.yourname.cloudlink.ui.home.HomeScreen
import com.yourname.cloudlink.ui.login.LoginScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
            HomeScreen(onLogout = { /* nav effect handles it */ })
        }
    }
}
