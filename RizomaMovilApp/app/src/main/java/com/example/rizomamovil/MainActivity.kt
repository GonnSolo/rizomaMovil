package com.example.rizomamovil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.rizomamovil.data.Contact
import com.example.rizomamovil.data.CryptoStorage
import com.example.rizomamovil.data.EncryptedChatMessage
import com.example.rizomamovil.theme.*
import com.example.rizomamovil.ui.ChatScreen
import com.example.rizomamovil.ui.ConfigScreen
import com.example.rizomamovil.ui.ContactsScreen
import com.example.rizomamovil.ui.PasswordScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import go.Seq
import org.torproject.jni.TorService
import rizomacore.AndroidCallback
import rizomacore.NetworkManager
import rizomacore.Rizomacore
import java.io.File
import java.io.FileOutputStream

enum class Screen {
    PASSWORD_AUTH,
    CONTACTS,
    CONFIG,
    CHAT
}

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cryptoStorage: CryptoStorage
    private lateinit var networkManager: NetworkManager

    private var currentScreen by mutableStateOf(Screen.PASSWORD_AUTH)
    private var isFirstTime by mutableStateOf(false)
    private var passwordError by mutableStateOf<String?>(null)

    private var torStatus by mutableStateOf("INICIANDO")
    private val contacts = mutableStateListOf<Contact>()
    private var activeContact by mutableStateOf<Contact?>(null)
    private val activeChatMessages = mutableStateListOf<EncryptedChatMessage>()
    private var myNickname by mutableStateOf("MobilePeer")
    private var myColor by mutableStateOf("196")
    private var myOnionAddress by mutableStateOf("")
    private var showSecretPromptDialog by mutableStateOf(false)
    private var pendingSecretAddress by mutableStateOf("")
    private var secretPromptInput by mutableStateOf("")
    private var pendingQrToken by mutableStateOf<String?>(null)
    private var qrAliasInput by mutableStateOf("")
    private var qrSecretInput by mutableStateOf("")
    private var qrImportError by mutableStateOf<String?>(null)
    private var qrImportSuccess by mutableStateOf<String?>(null)

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim()
        if (!contents.isNullOrEmpty()) {
            pendingQrToken = contents
            qrAliasInput = ""
            qrSecretInput = ""
            qrImportError = null
        }
    }

    // Mapa reactivo de adjuntos multimedia indexado por nombre/ruta para renderizar vistas previas
    // sin romper la estructura actual de EncryptedChatMessage ni CryptoStorage.
    val attachmentsByFilename = mutableStateMapOf<String, AttachmentItemUi>()

    private val torStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                TorService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS)
                        ?: intent.getStringExtra("EXTRA_STATUS")
                        ?: intent.getStringExtra("org.torproject.android.intent.extra.STATUS")
                        ?: return
                    torStatus = if (status == TorService.STATUS_ON) "ON" else status
                    if (status == TorService.STATUS_ON) {
                        checkAndUpdateOnionAddress()
                    }
                }
                TorService.ACTION_ERROR -> {
                    torStatus = "ERROR"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cryptoStorage = CryptoStorage(applicationContext)
        myNickname = cryptoStorage.getMyNickname()
        myColor = cryptoStorage.getMyColor()
        myOnionAddress = cryptoStorage.getMyOnionAddress()

        // Determine if first time setup or auto-unlock
        if (!cryptoStorage.isPasswordConfigured()) {
            isFirstTime = true
            currentScreen = Screen.PASSWORD_AUTH
        } else {
            isFirstTime = false
            if (cryptoStorage.tryAutoUnlock()) {
                loadContactsData()
                currentScreen = Screen.CONTACTS
            } else {
                currentScreen = Screen.PASSWORD_AUTH
            }
        }

        // 1. Inicializar contexto GoMobile
        try {
            Seq.setContext(applicationContext)
        } catch (_: Throwable) {}

        // 2. Inicializar NetworkManager
        networkManager = Rizomacore.newNetworkManager()
        networkManager.initProxy("127.0.0.1:9050")
        // ControlSocket de TorService (tor-android arranca Tor con --ControlSocket y
        // --CookieAuthentication 0). El core lo usa para publicar el .onion al hospedar
        // (p. ej. THE VOID), igual que Rizoma Desktop lo hace con bine.
        networkManager.initTorControl(
            "unix:" + File(getDir("TorService", Context.MODE_PRIVATE), "ControlSocket").absolutePath
        )

        val downloadDir = File(getExternalFilesDir(null), "loadingbay_in")
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        networkManager.setStorageDirectory(downloadDir.absolutePath)

        // 3. Callback de GoMobile con soporte multimedia estilo Briar
        // CORRECCIÓN CRÍTICA: En network.go, `fromPeer` durante transferencias de archivos suele ser
        // "Host" o "anonymous", lo cual antes no coincidía con `activeContact.alias` e impedía
        // que los archivos recibidos aparecieran en pantalla. Ahora se entregan al chat activo.
        val goCallback = object : AndroidCallback {
            override fun onSystemMessage(msg: String?) {
                if (msg != null && msg.startsWith("AUTH_SECRET_REQUIRED:")) {
                    val addr = msg.removePrefix("AUTH_SECRET_REQUIRED:")
                    mainHandler.post {
                        pendingSecretAddress = addr
                        secretPromptInput = ""
                        showSecretPromptDialog = true
                    }
                }
            }

            override fun onPeerMessage(senderName: String?, senderColor: String?, msg: String?) {
                val safeName = senderName?.ifEmpty { "Anon" } ?: "Anon"
                val safeMsg = msg ?: ""
                val safeColor = senderColor ?: "196"

                val chatMsg = EncryptedChatMessage(
                    sender = safeName,
                    text = safeMsg,
                    color = safeColor,
                    timestamp = System.currentTimeMillis()
                )

                mainHandler.post {
                    val contact = getOrCreatePeerContact(safeName)
                    cryptoStorage.saveMessage(contact.alias, chatMsg)

                    if (activeContact?.alias == contact.alias) {
                        activeChatMessages.add(chatMsg)
                    }
                }
            }

            override fun onFileOffer(filename: String?, fileSize: Long, fromPeer: String?) {
                val safePeer = fromPeer?.ifEmpty { "Peer" } ?: "Peer"
                val safeName = MultimediaAttachmentHelper.sanitizeFilename(filename ?: "archivo")
                val offerItem = MultimediaAttachmentHelper.createIncomingOfferItem(safeName, fileSize)
                val sizeFormatted = MultimediaAttachmentHelper.formatBytes(fileSize)

                val infoMsg = EncryptedChatMessage(
                    sender = "SISTEMA",
                    text = "📥 Recibiendo multimedia: $safeName ($sizeFormatted) [0/${offerItem.totalChunks} chunks]",
                    color = "VOID",
                    timestamp = System.currentTimeMillis()
                )

                mainHandler.post {
                    val contact = getOrCreatePeerContact(safePeer)
                    cryptoStorage.saveMessage(contact.alias, infoMsg)

                    attachmentsByFilename[safeName] = offerItem
                    if (activeContact?.alias == contact.alias) {
                        activeChatMessages.add(infoMsg)
                    }
                }
            }

            override fun onFileProgress(filename: String?, progress: Long, total: Long) {
                val safeName = MultimediaAttachmentHelper.sanitizeFilename(filename ?: return)
                val safeTotal = total.coerceAtLeast(1L)
                val pct = ((progress.toFloat() / safeTotal.toFloat()) * 100f).toInt().coerceIn(0, 100)

                mainHandler.post {
                    val existing = attachmentsByFilename[safeName]
                    if (existing != null) {
                        attachmentsByFilename[safeName] = existing.copy(
                            chunksCompleted = progress,
                            totalChunks = safeTotal
                        )
                    }

                    // Actualizar en tiempo real el mensaje de progreso en la ventana de chat
                    val idx = activeChatMessages.indexOfLast {
                        it.sender == "SISTEMA" && it.text.contains(safeName) && it.text.startsWith("📥")
                    }
                    if (idx >= 0) {
                        val old = activeChatMessages[idx]
                        activeChatMessages[idx] = old.copy(
                            text = "📥 Recibiendo multimedia: $safeName — $pct% ($progress/$safeTotal chunks)"
                        )
                    }
                }
            }

            override fun onFileReceived(filename: String?, localPath: String?, fromPeer: String?) {
                val safePeer = fromPeer?.ifEmpty { "Peer" } ?: "Peer"
                val safeName = MultimediaAttachmentHelper.sanitizeFilename(filename ?: "archivo")
                val safePath = localPath ?: ""
                val finalItem = MultimediaAttachmentHelper.finalizeReceivedItem(safeName, safePath)
                val dims = if (finalItem.width > 0 && finalItem.height > 0) " (${finalItem.width}x${finalItem.height}px)" else ""

                val doneMsg = EncryptedChatMessage(
                    sender = safePeer,
                    text = "📎 [MULTIMEDIA RECIBIDO: $safeName]$dims\nTipo: ${finalItem.mimeType} (${MultimediaAttachmentHelper.formatBytes(finalItem.fileSize)})\nGuardado en: $safePath",
                    color = "46",
                    timestamp = System.currentTimeMillis()
                )

                mainHandler.post {
                    val contact = getOrCreatePeerContact(safePeer)
                    cryptoStorage.saveMessage(contact.alias, doneMsg)

                    attachmentsByFilename[safeName] = finalItem
                    if (activeContact?.alias == contact.alias) {
                        // Reemplazar la línea de progreso por la confirmación final o agregarla
                        val progressIdx = activeChatMessages.indexOfLast {
                            it.sender == "SISTEMA" && it.text.contains(safeName) && it.text.startsWith("📥")
                        }
                        if (progressIdx >= 0) {
                            activeChatMessages[progressIdx] = doneMsg
                        } else {
                            activeChatMessages.add(doneMsg)
                        }
                    }
                }
            }
        }
        networkManager.startEventLoop(goCallback)

        // 4. Iniciar servicio Tor
        startTorService()

        setContent {
            RizomaMovilTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = TerminalBackground
                ) {
                    when (currentScreen) {
                        Screen.PASSWORD_AUTH -> {
                            PasswordScreen(
                                isFirstTime = isFirstTime,
                                errorMessage = passwordError,
                                onPasswordEntered = { password, requireOnStart ->
                                    handlePasswordSubmit(password, requireOnStart)
                                },
                                onResetPassword = {
                                    handleResetPassword()
                                }
                            )
                        }

                        Screen.CONTACTS -> {
                            ContactsScreen(
                                contacts = contacts,
                                torStatus = torStatus,
                                myNickname = myNickname,
                                myOnionAddress = myOnionAddress,
                                onOpenChat = { contact ->
                                    openChatWithContact(contact)
                                },
                                onOpenVoid = {
                                    openTheVoidChat()
                                },
                                onOpenConfig = {
                                    currentScreen = Screen.CONFIG
                                },
                                onAddContact = { newContact ->
                                    cryptoStorage.saveContact(newContact)
                                    loadContactsData()
                                },
                                onDeleteContact = { slot ->
                                    cryptoStorage.deleteContact(slot)
                                    loadContactsData()
                                },
                                onScanQr = {
                                    launchQrScanner()
                                }
                            )
                        }

                        Screen.CONFIG -> {
                            BackHandler {
                                currentScreen = Screen.CONTACTS
                            }

                            ConfigScreen(
                                currentAlias = myNickname,
                                currentColor = myColor,
                                requirePasswordOnStart = cryptoStorage.isRequirePasswordOnStart(),
                                onSaveAlias = { newAlias ->
                                    myNickname = newAlias
                                    cryptoStorage.setMyNickname(newAlias)
                                },
                                onSaveColor = { newColor ->
                                    myColor = newColor
                                    cryptoStorage.setMyColor(newColor)
                                },
                                onToggleRequirePassword = { require ->
                                    cryptoStorage.setRequirePasswordOnStart(require)
                                },
                                onChangePassword = { oldPass, newPass ->
                                    cryptoStorage.reEncryptAllWithNewPassword(oldPass, newPass)
                                },
                                onBack = {
                                    currentScreen = Screen.CONTACTS
                                }
                            )
                        }

                        Screen.CHAT -> {
                            BackHandler {
                                currentScreen = Screen.CONTACTS
                                activeContact = null
                            }

                            val alias = activeContact?.alias ?: "Chat"
                            ChatScreen(
                                contactAlias = alias,
                                messages = activeChatMessages,
                                onSendMessage = { text ->
                                    sendMessage(text)
                                },
                                onSendFile = { filename, bytes ->
                                    sendFile(filename, bytes)
                                },
                                onBack = {
                                    currentScreen = Screen.CONTACTS
                                    activeContact = null
                                }
                            )
                        }
                    }

                    if (showSecretPromptDialog) {
                        Dialog(onDismissRequest = {
                            showSecretPromptDialog = false
                            networkManager.cancelSecret()
                        }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TerminalBackground)
                                    .border(1.dp, RizomaRed, RoundedCornerShape(2.dp))
                                    .padding(16.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "// AUTENTICACIÓN REQUERIDA",
                                        color = RizomaRed,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    val displayAddr = if (pendingSecretAddress.length > 22) pendingSecretAddress.take(10) + "..." + pendingSecretAddress.takeLast(8) else pendingSecretAddress
                                    Text(
                                        text = "El servidor en $displayAddr requiere un secret de sesión.",
                                        color = TerminalTextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                    BasicTextField(
                                        value = secretPromptInput,
                                        onValueChange = { secretPromptInput = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, TerminalBorder, RoundedCornerShape(2.dp))
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        textStyle = TextStyle(
                                            color = TerminalText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        ),
                                        cursorBrush = SolidColor(RizomaRed),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            autoCorrectEnabled = false
                                        ),
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clickable {
                                                    showSecretPromptDialog = false
                                                    networkManager.cancelSecret()
                                                }
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
                                                    val entered = secretPromptInput.trim()
                                                    showSecretPromptDialog = false
                                                    networkManager.submitSecret(entered)
                                                    if (activeContact != null && entered.isNotEmpty()) {
                                                        val updated = activeContact!!.copy(secret = entered)
                                                        activeContact = updated
                                                        cryptoStorage.saveContact(updated)
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "[ CONECTAR ]",
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

                    pendingQrToken?.let { token ->
                        AlertDialog(
                            onDismissRequest = { pendingQrToken = null },
                            title = {
                                Text(
                                    "AGREGAR CONTACTO QR",
                                    fontFamily = FontFamily.Monospace,
                                    color = RizomaRed
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Escribe el alias y el secreto que aparecen debajo del QR de Rizoma.",
                                        fontFamily = FontFamily.Monospace,
                                        color = TerminalText,
                                        fontSize = 12.sp
                                    )
                                    OutlinedTextField(
                                        value = qrAliasInput,
                                        onValueChange = { qrAliasInput = it },
                                        label = { Text("Alias mostrado bajo el QR") },
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = qrSecretInput,
                                        onValueChange = { qrSecretInput = it },
                                        label = { Text("Secreto de sesión") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation()
                                    )
                                    qrImportError?.let { message ->
                                        Text(
                                            text = message,
                                            color = RizomaRed,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { importQrContact(token) }) {
                                    Text("GUARDAR", color = RizomaRed)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingQrToken = null }) {
                                    Text("CANCELAR", color = TerminalTextMuted)
                                }
                            },
                            containerColor = TerminalBackground
                        )
                    }

                    qrImportSuccess?.let { message ->
                        AlertDialog(
                            onDismissRequest = { qrImportSuccess = null },
                            title = {
                                Text("CONTACTO AGREGADO", color = RizomaRed, fontFamily = FontFamily.Monospace)
                            },
                            text = {
                                Text(message, color = TerminalText, fontFamily = FontFamily.Monospace)
                            },
                            confirmButton = {
                                TextButton(onClick = { qrImportSuccess = null }) {
                                    Text("ACEPTAR", color = RizomaRed)
                                }
                            },
                            containerColor = TerminalBackground
                        )
                    }
                }
            }
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Apunta al QR de Rizoma")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun importQrContact(token: String) {
        val alias = qrAliasInput.trim()
        val secret = qrSecretInput.trim()
        if (alias.isEmpty() || secret.isEmpty()) {
            qrImportError = "Escribe el alias y el secreto que aparecen bajo el QR."
            return
        }

        try {
            // Desktop Rizoma stores only the encrypted onion address in the QR payload.
            // DecryptOnion uses the typed alias as its key, matching the desktop format.
            val decoded = Rizomacore.decryptOnion(token, alias).trim().removeSuffix(".onion")
            if (!decoded.matches(Regex("^[a-z2-7]{56}$"))) {
                qrImportError = "El QR no contiene una dirección Onion v3 válida."
                return
            }

            val onionAddress = "$decoded.onion"
            if (contacts.any { it.onionAddress.equals(onionAddress, ignoreCase = true) }) {
                qrImportError = "Ese contacto ya está guardado."
                return
            }

            val nextSlot = (contacts.maxOfOrNull { it.slot } ?: 0) + 1
            cryptoStorage.saveContact(
                Contact(
                    slot = nextSlot,
                    alias = alias,
                    onionAddress = onionAddress,
                    secret = secret
                )
            )
            loadContactsData()
            pendingQrToken = null
            qrImportError = null
            qrImportSuccess = "$alias se agregó a tus contactos."
        } catch (_: Exception) {
            qrImportError = "No se pudo abrir este QR. Verifica el alias y prueba otra vez."
        }
    }

    private fun checkAndUpdateOnionAddress() {
        val netOnion = networkManager.onionAddr
        if (!netOnion.isNullOrEmpty()) {
            val fullOnion = if (netOnion.endsWith(".onion")) netOnion else "$netOnion.onion"
            myOnionAddress = fullOnion
            cryptoStorage.setMyOnionAddress(fullOnion)
        }
    }

    private fun handlePasswordSubmit(password: String, requireOnStart: Boolean) {
        if (isFirstTime) {
            val ok = cryptoStorage.setupInitialPassword(password, requireOnStart)
            if (ok) {
                passwordError = null
                loadContactsData()
                currentScreen = Screen.CONTACTS
            } else {
                passwordError = "Error al configurar la contraseña."
            }
        } else {
            val ok = cryptoStorage.unlockWithPassword(password)
            if (ok) {
                passwordError = null
                loadContactsData()
                currentScreen = Screen.CONTACTS
            } else {
                passwordError = "Contraseña incorrecta."
            }
        }
    }

    private fun handleResetPassword() {
        cryptoStorage.resetAllData()
        contacts.clear()
        activeChatMessages.clear()
        activeContact = null
        attachmentsByFilename.clear()
        myNickname = cryptoStorage.getMyNickname()
        myColor = cryptoStorage.getMyColor()
        myOnionAddress = cryptoStorage.getMyOnionAddress()
        isFirstTime = true
        passwordError = "Datos reiniciados. Configura una nueva contraseña."
        currentScreen = Screen.PASSWORD_AUTH
    }

    private fun loadContactsData() {
        contacts.clear()
        contacts.addAll(cryptoStorage.loadContacts())
    }

    /**
     * Resolves an existing contact for an incoming peer or creates a new "Contacto Pendiente".
     * If created and the user is on the Contacts screen, automatically opens the chat.
     */
    private fun getOrCreatePeerContact(peerName: String): Contact {
        val safe = peerName.ifBlank { "Peer" }
        // 1. Search in contacts
        val existing = contacts.find {
            it.alias.equals(safe, ignoreCase = true) ||
            it.alias.equals("$safe (Contacto Pendiente)", ignoreCase = true) ||
            it.alias.contains(safe, ignoreCase = true)
        }
        if (existing != null) {
            return existing
        }

        // 2. Search if active contact matches
        if (activeContact != null && (activeContact?.alias.equals(safe, ignoreCase = true) || activeContact?.alias?.contains(safe, ignoreCase = true) == true)) {
            return activeContact!!
        }

        // 3. Create as "Contacto Pendiente"
        val pendingContact = Contact(
            slot = contacts.size + 1,
            alias = "$safe (Contacto Pendiente)",
            onionAddress = "",
            secret = ""
        )
        contacts.add(pendingContact)
        cryptoStorage.saveContact(pendingContact)

        // If currently on Contacts screen, auto-open chat with this pending contact
        if (currentScreen == Screen.CONTACTS) {
            activeContact = pendingContact
            activeChatMessages.clear()
            activeChatMessages.addAll(cryptoStorage.loadMessages(pendingContact.alias))
            currentScreen = Screen.CHAT
        }

        return pendingContact
    }

    private fun openChatWithContact(contact: Contact) {
        activeContact = contact
        activeChatMessages.clear()
        activeChatMessages.addAll(cryptoStorage.loadMessages(contact.alias))
        currentScreen = Screen.CHAT

        // Connect via Tor in background only if onionAddress is present
        if (contact.onionAddress.isNotBlank()) {
            Thread {
                try {
                    if (contact.secret.isNotEmpty()) {
                        networkManager.connectWithSecretAndroid(contact.onionAddress, contact.secret)
                    } else {
                        networkManager.connectAndroid(contact.onionAddress)
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }

    private fun openTheVoidChat() {
        val voidContact = Contact(
            slot = 0,
            alias = "THE VOID",
            onionAddress = Rizomacore.VoidAddress,
            secret = Rizomacore.VoidSecret
        )
        activeContact = voidContact
        activeChatMessages.clear()
        activeChatMessages.addAll(cryptoStorage.loadMessages("THE VOID"))
        currentScreen = Screen.CHAT

        Thread {
            try {
                networkManager.joinOrHostVoidAndroid()
            } catch (_: Exception) {}
        }.start()
    }

    private fun sendMessage(text: String) {
        val current = activeContact ?: return
        val chatMsg = EncryptedChatMessage(
            sender = myNickname,
            text = text,
            color = if (current.alias == "THE VOID") "VOID" else myColor,
            timestamp = System.currentTimeMillis()
        )

        // 1. Add locally
        activeChatMessages.add(chatMsg)

        // 2. Persist encrypted
        cryptoStorage.saveMessage(current.alias, chatMsg)

        // 3. Broadcast to peer
        Thread {
            try {
                networkManager.broadcastWithIdentityAndroid(text, myNickname, myColor)
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * Implementación del pipeline de envío de adjuntos de Briar (`AttachmentCreatorImpl` + `ImageCompressorImpl`):
     * 1. Sanitiza el nombre del archivo y detecta su tipo MIME.
     * 2. Si es una imagen (JPEG/PNG/WebP), aplica la compresión iterativa de Briar (máx 1000px,
     *    eliminación de metadatos EXIF y ajuste de calidad JPEG <= 256 KB) en segundo plano.
     * 3. Guarda copia local en `<externalFilesDir>/loadingbay_in/sent/` y transmite en chunks de 16 KB.
     */
    private fun sendFile(filename: String, bytes: ByteArray) {
        val current = activeContact ?: return
        if (bytes.isEmpty()) return

        Thread {
            try {
                var cleanName = MultimediaAttachmentHelper.sanitizeFilename(filename)
                var mimeType = MultimediaAttachmentHelper.resolveMimeTypeFromFilename(cleanName)
                var payloadBytes = bytes
                var dimsInfo = ""

                // Paso 1: Si es imagen, comprimir y limpiar EXIF como en Briar ImageCompressorImpl
                if (mimeType.startsWith("image/") && mimeType != "image/gif") {
                    val compressed = BriarImageCompressor.compressImage(bytes, mimeType)
                    payloadBytes = compressed.bytes
                    mimeType = compressed.mimeType
                    if (compressed.width > 0 && compressed.height > 0) {
                        dimsInfo = " [${compressed.width}x${compressed.height}px, EXIF limpio]"
                    }
                    if (!cleanName.lowercase().endsWith(".jpg") && !cleanName.lowercase().endsWith(".jpeg")) {
                        val base = cleanName.substringBeforeLast('.', cleanName)
                        cleanName = "$base.jpg"
                    }
                }

                // Paso 2: Guardar copia local para el remitente (estilo Briar addLocalAttachment)
                val sentDir = File(getExternalFilesDir(null), "loadingbay_in/sent").apply { mkdirs() }
                val localSentFile = File(sentDir, cleanName)
                FileOutputStream(localSentFile).use { it.write(payloadBytes) }

                val formattedSize = MultimediaAttachmentHelper.formatBytes(payloadBytes.size.toLong())
                val chatMsg = EncryptedChatMessage(
                    sender = myNickname,
                    text = "📎 Enviando multimedia: $cleanName ($formattedSize)$dimsInfo...",
                    color = if (current.alias == "THE VOID") "VOID" else myColor,
                    timestamp = System.currentTimeMillis()
                )

                mainHandler.post {
                    activeChatMessages.add(chatMsg)
                    cryptoStorage.saveMessage(current.alias, chatMsg)
                }

                // Paso 3: Enviar por Tor en chunks de 16 KB (compatible con RizomaCore.aar actual y nuevo)
                networkManager.sendFileBytesAndroid(cleanName, payloadBytes)

                mainHandler.post {
                    val sentMsg = EncryptedChatMessage(
                        sender = "SISTEMA",
                        text = "✅ Multimedia enviado: $cleanName ($formattedSize)\nCopia local: ${localSentFile.absolutePath}",
                        color = "46",
                        timestamp = System.currentTimeMillis()
                    )
                    activeChatMessages.add(sentMsg)
                    cryptoStorage.saveMessage(current.alias, sentMsg)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    val errMsg = EncryptedChatMessage(
                        sender = "SISTEMA",
                        text = "❌ Error enviando multimedia $filename: ${e.message}",
                        color = "VOID",
                        timestamp = System.currentTimeMillis()
                    )
                    activeChatMessages.add(errMsg)
                }
            }
        }.start()
    }

    private fun startTorService() {
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(torStatusReceiver, filter)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(torStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(torStatusReceiver, filter)
            }
        } catch (_: Exception) {}

        val torIntent = Intent(this, TorService::class.java).apply {
            action = TorService.ACTION_START
        }
        try {
            startService(torIntent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(torStatusReceiver)
            unregisterReceiver(torStatusReceiver)
        } catch (_: Exception) {}
        try {
            networkManager.close()
        } catch (_: Exception) {}
    }
}