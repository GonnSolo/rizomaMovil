package com.example.rizomamovil.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rizomamovil.data.EncryptedChatMessage
import com.example.rizomamovil.theme.*

private fun readUriData(context: Context, uri: Uri): Pair<String, ByteArray?> {
    var name = "archivo"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
    } catch (_: Exception) {}
    if (name == "archivo") {
        name = uri.lastPathSegment ?: "archivo"
    }
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
    return Pair(name, bytes)
}

@Composable
fun ChatScreen(
    contactAlias: String,
    messages: List<EncryptedChatMessage>,
    onSendMessage: (String) -> Unit,
    onSendFile: ((String, ByteArray) -> Unit)? = null,
    onBack: () -> Unit
) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && onSendFile != null) {
            val (filename, data) = readUriData(context, uri)
            if (data != null && data.isNotEmpty()) {
                onSendFile(filename, data)
            }
        }
    }

    val isVoid = contactAlias == "THE VOID"
    val accentColor = if (isVoid) RizomaVoidPurple else RizomaRed

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun submit() {
        val trimmed = inputText.text.trim()
        if (trimmed.isNotEmpty()) {
            onSendMessage(trimmed)
            inputText = TextFieldValue("")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Minimal Header: < [ ATRÁS ]    Alias
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
                text = contactAlias,
                color = accentColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isVoid) RizomaVoidPurple.copy(alpha = 0.5f) else TerminalBorder)
        )

        // Chat message list: strictly Username: message
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { msg ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${msg.sender}: ",
                        color = if (isVoid && msg.sender == "THE VOID") RizomaVoidPurple else parseProfileColor(msg.color),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = msg.text,
                        color = if (msg.text.startsWith("📎")) TerminalBorderLight else TerminalText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isVoid) RizomaVoidPurple.copy(alpha = 0.5f) else TerminalBorder)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Input Line: > Enter command...
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isVoid) RizomaVoidPurple.copy(alpha = 0.6f) else TerminalBorder, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "> ",
                color = accentColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(accentColor),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { submit() }
                ),
                decorationBox = { innerTextField ->
                    if (inputText.text.isEmpty()) {
                        Text(
                            text = "Enter command...",
                            color = TerminalTextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            )

            if (onSendFile != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clickable { filePickerLauncher.launch("*/*") }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "[+]",
                        color = accentColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clickable { submit() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "[>]",
                    color = accentColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
