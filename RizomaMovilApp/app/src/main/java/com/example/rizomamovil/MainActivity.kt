package com.example.rizomamovil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
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
import androidx.compose.material3.Surface
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
import go.Seq
import org.torproject.jni.TorService
import rizomacore.AndroidCallback
import rizomacore.NetworkManager
import rizomacore.Rizomacore

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

        val downloadDir = File(getExternalFilesDir(null), "loadingbay_in")
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        networkManager.setStorageDirectory(downloadDir.absolutePath)

        // 3. Callback de GoMobile
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

                // Save message encrypted under sender's contact alias or active chat
                val targetAlias = activeContact?.alias ?: safeName
                cryptoStorage.saveMessage(targetAlias, chatMsg)

                mainHandler.post {
                    if (activeContact != null && (activeContact?.alias.equals(safeName, ignoreCase = true) || activeContact?.alias == targetAlias)) {
                        activeChatMessages.add(chatMsg)
                    }
                }
            }

            override fun onFileOffer(filename: String?, fileSize: Long, fromPeer: String?) {
                val safePeer = fromPeer?.ifEmpty { "Peer" } ?: "Peer"
                val safeName = filename ?: "archivo"
                val sizeKb = (fileSize / 1024).coerceAtLeast(1)
                val infoMsg = EncryptedChatMessage(
                    sender = "SISTEMA",
                    text = "📥 Recibiendo archivo: $safeName (${sizeKb} KB) de $safePeer...",
                    color = "VOID",
                    timestamp = System.currentTimeMillis()
                )
                val targetAlias = activeContact?.alias ?: safePeer
                cryptoStorage.saveMessage(targetAlias, infoMsg)
                mainHandler.post {
                    if (activeContact != null && (activeContact?.alias.equals(safePeer, ignoreCase = true) || activeContact?.alias == targetAlias)) {
                        activeChatMessages.add(infoMsg)
                    }
                }
            }

            override fun onFileProgress(filename: String?, progress: Long, total: Long) {
                // Background transfer progress
            }

            override fun onFileReceived(filename: String?, localPath: String?, fromPeer: String?) {
                val safePeer = fromPeer?.ifEmpty { "Peer" } ?: "Peer"
                val safeName = filename ?: "archivo"
                val safePath = localPath ?: ""
                val doneMsg = EncryptedChatMessage(
                    sender = safePeer,
                    text = "📎 [ARCHIVO RECIBIDO: $safeName]\nGuardado en: $safePath",
                    color = "196",
                    timestamp = System.currentTimeMillis()
                )
                val targetAlias = activeContact?.alias ?: safePeer
                cryptoStorage.saveMessage(targetAlias, doneMsg)
                mainHandler.post {
                    if (activeContact != null && (activeContact?.alias.equals(safePeer, ignoreCase = true) || activeContact?.alias == targetAlias)) {
                        activeChatMessages.add(doneMsg)
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
                                    val displayAddr = if (pendingSecretAddress.length > 22) pendingSecretAddress.take(18) + "..." else pendingSecretAddress
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
                }
            }
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

    private fun loadContactsData() {
        contacts.clear()
        contacts.addAll(cryptoStorage.loadContacts())
    }

    private fun openChatWithContact(contact: Contact) {
        activeContact = contact
        activeChatMessages.clear()
        activeChatMessages.addAll(cryptoStorage.loadMessages(contact.alias))
        currentScreen = Screen.CHAT

        // Connect via Tor in background
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

    private fun sendFile(filename: String, bytes: ByteArray) {
        val current = activeContact ?: return
        val sizeKb = (bytes.size / 1024).coerceAtLeast(1)
        val chatMsg = EncryptedChatMessage(
            sender = myNickname,
            text = "📎 Enviando archivo: $filename ($sizeKb KB)...",
            color = if (current.alias == "THE VOID") "VOID" else myColor,
            timestamp = System.currentTimeMillis()
        )

        // 1. Add locally
        activeChatMessages.add(chatMsg)

        // 2. Persist encrypted
        cryptoStorage.saveMessage(current.alias, chatMsg)

        // 3. Send file via Go in background thread
        Thread {
            try {
                networkManager.sendFileBytesAndroid(filename, bytes)
                mainHandler.post {
                    val sentMsg = EncryptedChatMessage(
                        sender = "SISTEMA",
                        text = "✅ Archivo enviado exitosamente: $filename",
                        color = "VOID",
                        timestamp = System.currentTimeMillis()
                    )
                    activeChatMessages.add(sentMsg)
                    cryptoStorage.saveMessage(current.alias, sentMsg)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    val errMsg = EncryptedChatMessage(
                        sender = "SISTEMA",
                        text = "❌ Error enviando archivo $filename: ${e.message}",
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
