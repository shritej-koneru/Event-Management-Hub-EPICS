package com.example.eventmanagementhub.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    Box(Modifier.fillMaxSize().background(BodyBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.25f))

            Text(
                "Event Hub",
                style = MaterialTheme.typography.headlineMedium,
                color = NavyText,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isLogin) "Welcome back" else "Create your account",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
            Spacer(Modifier.weight(0.3f))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    if (!isLogin) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryPurple,
                                unfocusedBorderColor = CardBorder
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryPurple,
                            unfocusedBorderColor = CardBorder
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryPurple,
                            unfocusedBorderColor = CardBorder
                        )
                    )

                    errorMsg?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                errorMsg = "Email and password are required"
                                return@Button
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
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                        enabled = !loading
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text(if (isLogin) "Sign in" else "Sign up", fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { isLogin = !isLogin; errorMsg = null },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryPurple),
                        border = BorderStroke(1.5.dp, PrimaryPurple)
                    ) {
                        Text(
                            if (isLogin) "Create account" else "Back to sign in",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.35f))
            Text(
                "Demo account: alice@uni.edu / secret123",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }
    }
}