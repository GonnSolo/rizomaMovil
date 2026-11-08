package com.example.rizomamovil.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rizomamovil.data.Contact
import com.example.rizomamovil.theme.*
import org.json.JSONObject
import rizomacore.Rizomacore

data class UniversalInvite(
    val alias: String,
    val onion: String,
    val secret: String = ""
)

fun parseUniversalInvite(input: String): UniversalInvite? {
    val clean = input.trim()
    if (clean.isEmpty()) return null

    // 1. RIZOMA:v1:<base64>
    if (clean.startsWith("rizoma:v1:", ignoreCase = true)) {
        val b64 = clean.substring("rizoma:v1:".length).trim()
        try {
            val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            val jsonBytes = try {
                Base64.decode(b64, flags)
            } catch (_: Exception) {
                Base64.decode(b64, Base64.DEFAULT)
            }
            val jsonStr = String(jsonBytes, Charsets.UTF_8)
            val obj = JSONObject(jsonStr)
            val onion = obj.optString("onion", "").trim()
            if (onion.isNotEmpty()) {
                val fullOnion = if (onion.endsWith(".onion")) onion else "$onion.onion"
                return UniversalInvite(
                    alias = obj.optString("alias", "").trim(),
                    onion = fullOnion,
                    secret = obj.optString("secret", "").trim()
                )
            }
        } catch (_: Exception) {}
    }

    // 2. rizoma:// URI
    if (clean.startsWith("rizoma://", ignoreCase = true)) {
        try {
            val uri = Uri.parse(clean)
            val onion = uri.getQueryParameter("onion") ?: uri.getQueryParameter("addr")
            if (!onion.isNullOrEmpty()) {
                val fullOnion = if (onion.endsWith(".onion")) onion else "$onion.onion"
                return UniversalInvite(
                    alias = uri.getQueryParameter("alias") ?: "",
                    onion = fullOnion,
                    secret = uri.getQueryParameter("secret") ?: ""
                )
            }
        } catch (_: Exception) {}
    }

    // 3. Raw JSON
    if (clean.startsWith("{") && clean.endsWith("}")) {
        try {
            val obj = JSONObject(clean)
            val onion = obj.optString("onion", "").trim()
            if (onion.isNotEmpty()) {
                val fullOnion = if (onion.endsWith(".onion")) onion else "$onion.onion"
                return UniversalInvite(
                    alias = obj.optString("alias", "").trim(),
                    onion = fullOnion,
                    secret = obj.optString("secret", "").trim()
                )
            }
        } catch (_: Exception) {}
    }

    return null
}

fun createUniversalInvite(alias: String, onionAddr: String, secret: String = ""): String {
    val cleanAddr = onionAddr.trim()
    val fullOnion = if (cleanAddr.isNotEmpty() && !cleanAddr.endsWith(".onion")) "$cleanAddr.onion" else cleanAddr
    val json = JSONObject().apply {
        put("v", 1)
        put("alias", alias.trim())
        put("onion", fullOnion)
        if (secret.isNotEmpty()) {
            put("secret", secret.trim())
        }
    }
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    val b64 = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return "RIZOMA:v1:$b64"
}

@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    torStatus: String,
    myNickname: String,
    myOnionAddress: String,
    onOpenChat: (Contact) -> Unit,
    onOpenVoid: () -> Unit,
    onOpenConfig: () -> Unit,
    onAddContact: (Contact) -> Unit,
    onDeleteContact: (Int) -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showMyKeyDialog by remember { mutableStateOf(false) }

    var aliasInput by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }
    var detectedInfo by remember { mutableStateOf<String?>(null) }
    var detectedSecret by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Header: RIZOMA on left, TOR status and CONFIG button on top right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RIZOMA",
                color = RizomaRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 2.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "TOR: $torStatus",
                    color = if (torStatus == "ON") TerminalBorderLight else RizomaRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                // Top right CONFIG button
                Box(
                    modifier = Modifier
                        .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                        .clickable { onOpenConfig() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "[ CONFIG ]",
                        color = RizomaRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TerminalBorder)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Action Bar: My Key Button & Total Contacts Counter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "// CONTACTOS (${contacts.size})",
                color = TerminalTextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            // Button to share contact
            Box(
                modifier = Modifier
                    .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                    .clickable { showMyKeyDialog = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "[ COMPARTIR CONTACTO ]",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Dynamic Contacts List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Render existing contacts
            itemsIndexed(contacts) { index, contact ->
                val slotNumber = index + 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBackground)
                        .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                        .clickable { onOpenChat(contact) }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "[$slotNumber] ",
                                color = RizomaRed,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isPending = contact.alias.contains("Pendiente", ignoreCase = true)
                                    val cleanDisplayName = contact.alias
                                        .replace(" (Contacto Pendiente)", "")
                                        .replace(" [PENDIENTE]", "")
                                    Text(
                                        text = cleanDisplayName,
                                        color = if (isPending) RizomaRed else TerminalText,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    if (isPending) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "[PENDIENTE]",
                                            color = RizomaRed,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Delete button
                        Box(
                            modifier = Modifier
                                .clickable { onDeleteContact(contact.slot) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "[x]",
                                color = TerminalTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Exactly ONE "Agregar Contacto" at the bottom
            item {
                val nextSlot = contacts.size + 1
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBackground)
                        .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                        .clickable {
                            aliasInput = ""
                            keyInput = ""
                            detectedInfo = null
                            detectedSecret = ""
                            addError = null
                            showAddDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[$nextSlot] ",
                            color = TerminalBorderLight,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "+ AGREGAR CONTACTO",
                            color = TerminalTextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Public Void Room Slot with authentic Rizoma Void Purple
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalBackground)
                        .border(1.dp, RizomaVoidPurple, RoundedCornerShape(2.dp))
                        .clickable { onOpenVoid() }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[*] ",
                            color = RizomaVoidPurple,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "THE VOID (SALA PÚBLICA)",
                            color = RizomaVoidPurple,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // DIALOG 1: COMPARTIR MI CONTACTO
        if (showMyKeyDialog) {
            val universalInvite = remember(myOnionAddress, myNickname) {
                createUniversalInvite(myNickname, myOnionAddress)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBackground)
                    .border(1.dp, RizomaRed, RoundedCornerShape(2.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "// COMPARTIR MI CONTACTO",
                        color = RizomaRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Text(
                        text = "Comparte tu invitación para que otros te agreguen a Rizoma (Móvil o PC):",
                        color = TerminalTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row {
                            Text(text = "Alias: ", color = TerminalTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(text = myNickname, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Row {
                            Text(text = "Onion: ", color = TerminalTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(text = myOnionAddress.ifEmpty { "Iniciando Tor..." }, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "Invitación Universal:", color = TerminalTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text(
                            text = universalInvite,
                            color = TerminalBorderLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clickable { showMyKeyDialog = false }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "[ CERRAR ]",
                                color = TerminalTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Rizoma Onion Address", myOnionAddress)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Dirección .onion copiada", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "[ COPIAR .ONION ]",
                                color = TerminalText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Rizoma Universal Invite", universalInvite)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Invitación copiada (incluye alias y .onion)", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "[ COPIAR INVITACIÓN ]",
                                color = RizomaRed,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // DIALOG 2: AGREGAR CONTACTO (Dirección .onion directa o Invitación Universal)
        if (showAddDialog) {
            val nextSlot = contacts.size + 1
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBackground)
                    .border(1.dp, RizomaRed, RoundedCornerShape(2.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "// AGREGAR CONTACTO (SLOT $nextSlot)",
                        color = RizomaRed,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Text(
                        text = "Pega un código de Invitación Universal o ingresa la dirección .onion de Tor.",
                        color = TerminalTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    // Alias input
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Alias: ",
                            color = TerminalTextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        BasicTextField(
                            value = aliasInput,
                            onValueChange = { input ->
                                val invite = parseUniversalInvite(input)
                                if (invite != null) {
                                    if (invite.alias.isNotEmpty()) aliasInput = invite.alias
                                    keyInput = invite.onion
                                    detectedSecret = invite.secret
                                    detectedInfo = "✓ Invitación detectada: ${invite.alias.ifEmpty { "Peer" }}"
                                    addError = null
                                } else {
                                    aliasInput = input
                                    addError = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            textStyle = TextStyle(
                                color = TerminalText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(RizomaRed),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (aliasInput.isEmpty()) {
                                    Text(
                                        text = "ej: PC, Amigo...",
                                        color = TerminalTextMuted.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    // Onion o Invitación Universal input
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Dirección: ",
                            color = TerminalTextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        BasicTextField(
                            value = keyInput,
                            onValueChange = { input ->
                                val invite = parseUniversalInvite(input)
                                if (invite != null) {
                                    if (invite.alias.isNotEmpty()) aliasInput = invite.alias
                                    keyInput = invite.onion
                                    detectedSecret = invite.secret
                                    detectedInfo = "✓ Invitación detectada: ${invite.alias.ifEmpty { "Peer" }}"
                                    addError = null
                                } else {
                                    keyInput = input
                                    addError = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            textStyle = TextStyle(
                                color = TerminalText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(RizomaRed),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (keyInput.isEmpty()) {
                                    Text(
                                        text = "v4h...onion o RIZOMA:v1:...",
                                        color = TerminalTextMuted.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    if (detectedInfo != null) {
                        Text(
                            text = detectedInfo!!,
                            color = TerminalBorderLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    if (addError != null) {
                        Text(
                            text = addError!!,
                            color = RizomaRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clickable { showAddDialog = false }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "[ CANCELAR ]",
                                color = TerminalTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                .clickable {
                                    val safeAlias = aliasInput.trim()
                                    val rawKey = keyInput.trim()

                                    if (safeAlias.isEmpty() || rawKey.isEmpty()) {
                                        addError = "Ingresa el alias y la dirección .onion (o invitación)."
                                        return@clickable
                                    }

                                    // 1. Probar si el texto ingresado es una invitación universal
                                    val invite = parseUniversalInvite(rawKey)
                                    var resolvedAddress: String? = invite?.onion
                                    val resolvedSecret = detectedSecret.ifEmpty { invite?.secret ?: "" }

                                    // 2. Probar descifrado como Llave Encriptada legacy para este alias
                                    if (resolvedAddress == null) {
                                        try {
                                            val decrypted = Rizomacore.decryptOnion(rawKey, safeAlias)
                                            if (!decrypted.isNullOrEmpty()) {
                                                resolvedAddress = if (decrypted.endsWith(".onion")) decrypted else "$decrypted.onion"
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    // 3. Reconocimiento flexible de dirección .onion
                                    if (resolvedAddress == null) {
                                        val clean = rawKey.lowercase().trim()
                                        if (clean.contains(".onion")) {
                                            val match = Regex("[a-z2-7]{16,56}\\.onion").find(clean)
                                            resolvedAddress = match?.value ?: clean
                                        } else if (clean.length == 56 && clean.all { it in "abcdefghijklmnopqrstuvwxyz234567" }) {
                                            resolvedAddress = "$clean.onion"
                                        }
                                    }

                                    if (resolvedAddress.isNullOrEmpty()) {
                                        addError = if (rawKey.length < 20) {
                                            "Ingresa una dirección .onion de Tor válida (ej. abc...xyz.onion) o una invitación."
                                        } else {
                                            "Invitación o llave inválida para el alias '$safeAlias' (o dirección mal formada)."
                                        }
                                        return@clickable
                                    }

                                    onAddContact(
                                        Contact(
                                            slot = nextSlot,
                                            alias = safeAlias,
                                            onionAddress = resolvedAddress,
                                            secret = resolvedSecret
                                        )
                                    )
                                    showAddDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
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
                }
            }
        }
    }
}
