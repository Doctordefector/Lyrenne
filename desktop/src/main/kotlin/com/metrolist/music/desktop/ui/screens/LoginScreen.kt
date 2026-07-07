package com.metrolist.music.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.auth.BrowserLoginHelper
import com.metrolist.music.desktop.auth.CookieExtractResult
import kotlinx.coroutines.launch

enum class LoginStep {
    PICK_METHOD,
    WAITING_FOR_BROWSER,
    VALIDATING,
    SUCCESS,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loginStep by remember { mutableStateOf(LoginStep.PICK_METHOD) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    val hasBrowser = remember { BrowserLoginHelper.findBrowserExecutable() != null }

    fun handleCookieResult(result: CookieExtractResult) {
        when (result) {
            is CookieExtractResult.Success -> {
                loginStep = LoginStep.VALIDATING
                statusMessage = "Signing in..."

                scope.launch {
                    AuthManager.saveCredentials(
                        cookie = result.cookie,
                        visitorData = "",
                        dataSyncId = ""
                    ).onSuccess {
                        loginStep = LoginStep.SUCCESS
                        kotlinx.coroutines.delay(1500)
                        onLoginSuccess()
                    }.onFailure { e ->
                        error = "${e::class.simpleName}: ${e.message}"
                        loginStep = LoginStep.ERROR
                    }
                }
            }
            is CookieExtractResult.Error -> {
                error = result.message
                loginStep = LoginStep.ERROR
            }
        }
    }

    fun signInWithBrowser() {
        loginStep = LoginStep.WAITING_FOR_BROWSER
        statusMessage = "Opening browser..."

        scope.launch {
            val result = BrowserLoginHelper.loginWithBrowser { status ->
                statusMessage = status
            }
            handleCookieResult(result)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (loginStep) {
                            LoginStep.PICK_METHOD -> "Sign in to YouTube Music"
                            LoginStep.WAITING_FOR_BROWSER -> "Waiting for sign in..."
                            LoginStep.VALIDATING -> "Signing in..."
                            LoginStep.SUCCESS -> "Success!"
                            LoginStep.ERROR -> "Sign in Failed"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (loginStep) {
                LoginStep.PICK_METHOD -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Primary: Sign in with browser
                        if (hasBrowser) {
                            Card(
                                onClick = { signInWithBrowser() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.OpenInBrowser,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Sign in with browser",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            "Opens a browser window. Sign in, then close it.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.Login,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                LoginStep.WAITING_FOR_BROWSER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Sign in to YouTube Music in the browser window,\nthen close the browser when done.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(32.dp))
                        OutlinedButton(onClick = {
                            loginStep = LoginStep.PICK_METHOD
                        }) {
                            Text("Cancel")
                        }
                    }
                }

                LoginStep.VALIDATING -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(statusMessage, style = MaterialTheme.typography.titleMedium)
                    }
                }

                LoginStep.SUCCESS -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Signed in successfully!", style = MaterialTheme.typography.headlineSmall)
                    }
                }

                LoginStep.ERROR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Sign in failed", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error ?: "An unknown error occurred",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onBack) { Text("Cancel") }
                            Button(onClick = {
                                error = null
                                loginStep = LoginStep.PICK_METHOD
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Try Again")
                            }
                        }
                    }
                }
            }
        }
    }
}
