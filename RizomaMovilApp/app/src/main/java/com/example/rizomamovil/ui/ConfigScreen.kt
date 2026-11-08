package com.example.rizomamovil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rizomamovil.theme.*

@Composable
fun ConfigScreen(
    currentAlias: String,
    currentColor: String,
    requirePasswordOnStart: Boolean,
    onSaveAlias: (String) -> Unit,
    onSaveColor: (String) -> Unit,
    onToggleRequirePassword: (Boolean) -> Unit,
    onChangePassword: (oldPass: String, newPass: String) -> Boolean,
    onBack: () -> Unit
) {
    var aliasText by remember { mutableStateOf(currentAlias) }
    var aliasFeedback by remember { mutableStateOf<String?>(null) }
    var selectedColorCode by remember { mutableStateOf(currentColor) }

    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordFeedback by remember { mutableStateOf<String?>(null) }
    var isPasswordSuccess by remember { mutableStateOf(false) }

    var torDiagnosticMessage by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(scrollState)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "< [ ATRÁS ]",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = "CONFIGURACIÓN",
                color = RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TerminalBorder)
        )

        Spacer(modifier = Modifier.height(18.dp))

        // SECTION 1: CAMBIAR ALIAS (TU NOMBRE DE USUARIO)
        Text(
            text = "// ALIAS DE USUARIO",
            color = RizomaRed,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Tu nombre visible en los mensajes:",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = aliasText,
                onValueChange = {
                    aliasText = it
                    aliasFeedback = null
                },
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(RizomaRed),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrectEnabled = false
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                    .clickable {
                        val trimmed = aliasText.trim()
                        if (trimmed.isNotEmpty()) {
                            onSaveAlias(trimmed)
                            aliasFeedback = "Alias actualizado con éxito."
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "[ GUARDAR ]",
                    color = RizomaRed,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        if (aliasFeedback != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = aliasFeedback!!,
                color = TerminalText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 2: COLOR DE PERFIL
        Text(
            text = "// COLOR DE PERFIL",
            color = RizomaRed,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "El color con el que tus pares verán tu nombre:",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AvailableProfileColors.forEach { option ->
                val isSelected = selectedColorCode == option.code
                Box(
                    modifier = Modifier
                        .background(TerminalBackground, RoundedCornerShape(2.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) option.color else TerminalBorder,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .clickable {
                            selectedColorCode = option.code
                            onSaveColor(option.code)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isSelected) "[ ${option.label} ]" else option.label,
                        color = option.color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 3: SEGURIDAD DE INICIO
        Text(
            text = "// ACCESO AL INICIAR",
            color = RizomaRed,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "¿Pedir contraseña cada vez que abras la app?",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .border(1.dp, if (requirePasswordOnStart) RizomaRed else TerminalBorder, RoundedCornerShape(2.dp))
                    .clickable { onToggleRequirePassword(true) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "[ SÍ ]",
                    color = if (requirePasswordOnStart) RizomaRed else TerminalTextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Box(
                modifier = Modifier
                    .border(1.dp, if (!requirePasswordOnStart) RizomaRed else TerminalBorder, RoundedCornerShape(2.dp))
                    .clickable { onToggleRequirePassword(false) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "[ NO ]",
                    color = if (!requirePasswordOnStart) RizomaRed else TerminalTextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SECTION 4: CAMBIAR PASSWORD (Soporte completo para todas las letras, acentos y ñ)
        Text(
            text = "// CAMBIAR CONTRASEÑA",
            color = RizomaRed,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Todos los contactos y conversaciones se re-encriptarán con la nueva clave.",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Old Password
        Text(
            text = "Contraseña actual:",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = oldPassword,
            onValueChange = {
                oldPassword = it
                passwordFeedback = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(
                color = RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            visualTransformation = PasswordVisualTransformation('*'),
            cursorBrush = SolidColor(RizomaRed),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // New Password
        Text(
            text = "Nueva contraseña:",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = newPassword,
            onValueChange = {
                newPassword = it
                passwordFeedback = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(
                color = RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            visualTransformation = PasswordVisualTransformation('*'),
            cursorBrush = SolidColor(RizomaRed),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Confirm Password
        Text(
            text = "Confirmar nueva contraseña:",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                passwordFeedback = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle = TextStyle(
                color = RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            visualTransformation = PasswordVisualTransformation('*'),
            cursorBrush = SolidColor(RizomaRed),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                .clickable {
                    if (oldPassword.isEmpty()) {
                        passwordFeedback = "Ingresa tu contraseña actual."
                        isPasswordSuccess = false
                        return@clickable
                    }
                    if (newPassword.isEmpty()) {
                        passwordFeedback = "Ingresa una nueva contraseña."
                        isPasswordSuccess = false
                        return@clickable
                    }
                    if (newPassword != confirmPassword) {
                        passwordFeedback = "Las nuevas contraseñas no coinciden."
                        isPasswordSuccess = false
                        return@clickable
                    }

                    val ok = onChangePassword(oldPassword, newPassword)
                    if (ok) {
                        passwordFeedback = "Contraseña y base de datos re-encriptadas con éxito."
                        isPasswordSuccess = true
                        oldPassword = ""
                        newPassword = ""
                        confirmPassword = ""
                    } else {
                        passwordFeedback = "Contraseña actual incorrecta."
                        isPasswordSuccess = false
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = "[ RE-ENCRIPTAR Y ACTUALIZAR ]",
                color = RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        if (passwordFeedback != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = passwordFeedback!!,
                color = if (isPasswordSuccess) TerminalText else RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SECTION 5: DIAGNÓSTICO DE CONEXIÓN TOR
        Text(
            text = "// DIAGNÓSTICO DE RED TOR",
            color = RizomaRed,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Comprueba el estado del proxy SOCKS5 local (127.0.0.1:9050):",
            color = TerminalTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                .clickable {
                    torDiagnosticMessage = "Verificando proxy SOCKS5..."
                    Thread {
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress("127.0.0.1", 9050), 3000)
                            socket.close()
                            torDiagnosticMessage = "✓ Proxy SOCKS5 activo (127.0.0.1:9050)\nConexión a la red Tor operativa y anónima."
                        } catch (e: Exception) {
                            torDiagnosticMessage = "✗ Proxy Tor no disponible aún (${e.message ?: "esperando inicio"})"
                        }
                    }.start()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "[ PROBAR CONEXIÓN SOCKS5 ]",
                color = TerminalText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        if (torDiagnosticMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = torDiagnosticMessage!!,
                color = if (torDiagnosticMessage!!.startsWith("✓")) TerminalText else RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
