package com.lyrenne.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyrenne.desktop.auth.AuthManager
import com.lyrenne.desktop.auth.SavedAccount
import kotlinx.coroutines.launch

/**
 * The other accounts signed in on this copy of Lyrenne, one click away (issue #11).
 *
 * Shown under the account card in Settings, signed in or not: after signing one account out the
 * rest are still here, which is the whole point on a shared PC. "Add another account" runs the
 * ordinary browser sign-in; AuthManager keeps whoever was signed in rather than replacing them.
 */
@Composable
fun AccountSwitcher(onAddAccount: () -> Unit) {
    val saved by AuthManager.savedAccounts.collectAsState()
    val authState by AuthManager.authState.collectAsState()
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<SavedAccount?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (saved.isNotEmpty()) {
            Text(
                "Other accounts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
            )
        }
        saved.forEach { account ->
            ListItem(
                leadingContent = { AccountAvatar(account, size = 40) },
                headlineContent = { Text(account.accountInfo?.name ?: "YouTube Music account") },
                supportingContent = {
                    val detail = when {
                        account.sessionExpired -> "Signed out by YouTube. Sign in to it again to use it."
                        !account.accountInfo?.email.isNullOrBlank() -> account.accountInfo?.email
                        !account.accountInfo?.channelHandle.isNullOrBlank() ->
                            account.accountInfo?.channelHandle?.let { if (it.startsWith("@")) it else "@$it" }
                        else -> null
                    }
                    detail?.let {
                        Text(
                            it,
                            color = if (account.sessionExpired) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (switching == account.id) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (account.sessionExpired) {
                            // Signing the same account in again replaces this entry.
                            TextButton(onClick = onAddAccount, enabled = switching == null) { Text("Sign in") }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    error = null
                                    switching = account.id
                                    scope.launch {
                                        AuthManager.switchTo(account.id).onFailure { error = it.message }
                                        switching = null
                                    }
                                },
                                enabled = switching == null
                            ) {
                                Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Switch")
                            }
                        }
                        IconButton(onClick = { removing = account }, enabled = switching == null) {
                            Icon(Icons.Default.Close, "Remove this account")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clip(MaterialTheme.shapes.medium)
            )
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        if (authState.isLoggedIn) {
            TextButton(onClick = onAddAccount, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add another account")
            }
        }
    }

    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${account.accountInfo?.name ?: "this account"}?") },
            text = {
                Text(
                    "Lyrenne forgets this account and deletes its saved sign-in. " +
                        "You can add it again later by signing in."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { AuthManager.removeSaved(account.id) }
                        removing = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AccountAvatar(account: SavedAccount, size: Int) {
    val url = account.accountInfo?.avatarUrl
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    account.accountInfo?.name?.firstOrNull()?.uppercase() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
