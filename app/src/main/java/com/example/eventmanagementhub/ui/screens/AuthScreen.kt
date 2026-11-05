package com.example.eventmanagementhub.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.eventmanagementhub.ui.components.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.SessionStore
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    onAuthed: (isNewAccount: Boolean) -> Unit
) {
    var isLogin by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = remember { HubRepository() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // A 401 invalidates the token while SessionStore.clear() is still running;
        // restoring it here would bounce straight back into the rejected session.
        if (AuthProvider.consumeInvalidation()) return@LaunchedEffect
        val saved = SessionStore.token(context)
        if (!saved.isNullOrBlank()) {
            AuthProvider.token = saved
            repository.setToken(saved)
            repository.restoreSession()
            onAuthed(false)
        }
    }

    fun finish(token: String, newAccount: Boolean) {
        scope.launch {
            // Persist before navigating, otherwise a fast restart/sign-out race
            // can leave the token unwritten.
            SessionStore.save(context, token)
            onAuthed(newAccount)
        }
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            Text(
                "Event Hub",
                style = MaterialTheme.typography.headlineMedium,
                color = Ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isLogin) "Sign in to see events, routes and announcements" else "Create your account",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink2
            )
            Spacer(Modifier.height(24.dp))

            HubCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (!isLogin) {
                        LabeledField("Name") {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                                shape = ControlShape,
                                colors = hubFieldColors()
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    LabeledField("Email") {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Email" },
                            shape = ControlShape,
                            colors = hubFieldColors()
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    LabeledField("Password") {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password" },
                            shape = ControlShape,
                            colors = hubFieldColors()
                        )
                    }

                    errorMsg?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = Danger, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(20.dp))

                    PrimaryButton(
                        text = if (isLogin) "Sign in" else "Sign up",
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                errorMsg = "Email and password are required"
                                return@PrimaryButton
                            }
                            scope.launch {
                                loading = true
                                errorMsg = null
                                val result = if (isLogin) {
                                    repository.login(email.trim(), password)
                                } else {
                                    repository.register(name.trim(), email.trim(), password)
                                }
                                loading = false
                                when (result) {
                                    is Result.Success -> {
                                        repository.cachedUser = result.data.user
                                        finish(result.data.token, !isLogin)
                                    }
                                    is Result.Error -> errorMsg = result.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        loading = loading
                    )

                    Spacer(Modifier.height(12.dp))

                    SecondaryButton(
                        text = if (isLogin) "Create account" else "Back to sign in",
                        onClick = { isLogin = !isLogin; errorMsg = null },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Demo account: alice@uni.edu / secret123",
                style = MaterialTheme.typography.bodySmall,
                color = Ink3
            )
        }
    }
}
