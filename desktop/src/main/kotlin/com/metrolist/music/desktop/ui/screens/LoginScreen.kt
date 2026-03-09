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
import com.metrolist.music.desktop.auth.BrowserCookieExtractor
import com.metrolist.music.desktop.auth.BrowserProfile
import com.metrolist.music.desktop.auth.CookieExtractResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

private const val YOUTUBE_MUSIC_URL = "https://music.youtube.com/"

enum class LoginStep {
    PICK_BROWSER,
    EXTRACTING,
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
    var loginStep by remember { mutableStateOf(LoginStep.PICK_BROWSER) }
    var error by remember { mutableStateOf<String?>(null) }
    var browsers by remember { mutableStateOf<List<BrowserProfile>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("") }

    // Detect browsers on first composition
    LaunchedEffect(Unit) {
        browsers = withContext(Dispatchers.IO) {
            BrowserCookieExtractor.detectBrowsers()
        }
    }

    fun importFromBrowser(browser: BrowserProfile) {
        loginStep = LoginStep.EXTRACTING
        statusMessage = "Reading cookies from ${browser.name}..."

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                BrowserCookieExtractor.extractYouTubeCookies(browser)
            }

            when (result) {
                is CookieExtractResult.Success -> {
                    loginStep = LoginStep.VALIDATING
                    statusMessage = "Signing in..."

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
                is CookieExtractResult.Error -> {
                    error = result.message
                    loginStep = LoginStep.ERROR
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (loginStep) {
                            LoginStep.PICK_BROWSER -> "Sign in to YouTube Music"
                            LoginStep.EXTRACTING -> "Reading browser cookies..."
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
                LoginStep.PICK_BROWSER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Step 1: Make sure you're signed in
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Make sure you're signed in to YouTube Music in your browser first.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            Desktop.getDesktop().browse(URI(YOUTUBE_MUSIC_URL))
                                        } catch (_: Exception) { }
                                    }
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Open YouTube Music")
                                }
                            }
                        }

                        // Step 2: Pick browser
                        Text(
                            "Import cookies from your browser",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Select the browser where you're signed in to YouTube Music. Metrolist will read your login cookies automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (browsers.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "No supported browsers detected. Install Chrome, Edge, Opera, Brave, or Vivaldi.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        browsers.forEach { browser ->
                            BrowserCard(
                                browser = browser,
                                onClick = { importFromBrowser(browser) }
                            )
                        }

                        // Refresh button
                        TextButton(
                            onClick = {
                                scope.launch {
                                    browsers = withContext(Dispatchers.IO) {
                                        BrowserCookieExtractor.detectBrowsers()
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh browser list")
                        }
                    }
                }

                LoginStep.EXTRACTING, LoginStep.VALIDATING -> {
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
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (loginStep == LoginStep.EXTRACTING)
                                "This may take a few seconds..."
                            else
                                "Verifying your account...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        Text(
                            "Signed in successfully!",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Redirecting to your library...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        Text(
                            "Sign in failed",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error ?: "An unknown error occurred",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onBack) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    error = null
                                    loginStep = LoginStep.PICK_BROWSER
                                }
                            ) {
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

@Composable
private fun BrowserCard(
    browser: BrowserProfile,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    browser.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    browser.userDataDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.Login,
                contentDescription = "Import",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
