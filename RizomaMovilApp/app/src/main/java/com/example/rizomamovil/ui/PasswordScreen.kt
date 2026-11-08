package com.example.rizomamovil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.rizomamovil.theme.*

enum class PasswordStep {
    ENTER_PASSWORD,
    ASK_REQUIRE_ON_START
}

@Composable
fun PasswordScreen(
    isFirstTime: Boolean,
    errorMessage: String? = null,
    onPasswordEntered: (password: String, requireOnStart: Boolean) -> Unit,
    onResetPassword: (() -> Unit)? = null
) {
    var passwordStep by remember { mutableStateOf(PasswordStep.ENTER_PASSWORD) }
    var passwordText by remember { mutableStateOf("") }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(passwordStep) {
        if (passwordStep == PasswordStep.ENTER_PASSWORD) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(24.dp)
    ) {
        if (passwordStep == PasswordStep.ENTER_PASSWORD) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Terminal prompt: Password: ********█
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Password: ",
                        color = RizomaRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    BasicTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = RizomaRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        visualTransformation = PasswordVisualTransformation('*'),
                        cursorBrush = SolidColor(RizomaRed),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (passwordText.isNotEmpty()) {
                                    if (isFirstTime) {
                                        passwordStep = PasswordStep.ASK_REQUIRE_ON_START
                                    } else {
                                        onPasswordEntered(passwordText, false)
                                    }
                                }
                            }
                        ),
                        singleLine = true
                    )
                }

                if (!errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage,
                        color = RizomaRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Enter button (tactile fallback for virtual keyboard)
                Box(
                    modifier = Modifier
                        .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                        .clickable {
                            if (passwordText.isNotEmpty()) {
                                if (isFirstTime) {
                                    passwordStep = PasswordStep.ASK_REQUIRE_ON_START
                                } else {
                                    onPasswordEntered(passwordText, false)
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "[ ENTRAR ]",
                        color = TerminalText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                if (onResetPassword != null) {
                    Spacer(modifier = Modifier.height(28.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, RizomaRed.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                            .clickable {
                                showResetConfirmation = true
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Reiniciar contraseña (perderás todos tus contactos e información)",
                            color = RizomaRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        } else {
            // Step 2 for first time: ask if password should be required on start
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "CONFIGURACIÓN DE SEGURIDAD",
                    color = RizomaRed,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "¿Quieres que pida la contraseña cada vez que entres a la aplicación?",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, TerminalBorder, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                            .clickable {
                                onPasswordEntered(passwordText, true)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "[ 1. SÍ ]",
                            color = RizomaRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .border(1.dp, TerminalBorder, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                            .clickable {
                                onPasswordEntered(passwordText, false)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "[ 2. NO ]",
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        if (showResetConfirmation) {
            Dialog(onDismissRequest = { showResetConfirmation = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBackground)
                        .border(1.dp, RizomaRed, RoundedCornerShape(2.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "// ADVERTENCIA: REINICIAR CONTRASEÑA",
                            color = RizomaRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Esta acción es irreversible. Se eliminarán permanentemente todos tus contactos, chats e información cifrada.",
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                    .clickable { showResetConfirmation = false }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "[ CANCELAR ]",
                                    color = TerminalText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .background(RizomaRed)
                                    .clickable {
                                        showResetConfirmation = false
                                        passwordText = ""
                                        onResetPassword?.invoke()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "[ SÍ, REINICIAR TODO ]",
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
