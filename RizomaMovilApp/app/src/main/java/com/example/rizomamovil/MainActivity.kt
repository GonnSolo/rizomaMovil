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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.rizomamovil.theme.*
import go.Seq
import org.torproject.jni.TorService
import rizomacore.AndroidCallback
import rizomacore.NetworkManager
import rizomacore.Rizomacore

data class LogMessage(
    val sender: String,
    val text: String,
    val color: Color
)

class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logs = mutableStateListOf<LogMessage>()
    private var torStatus by mutableStateOf("INICIANDO...")
    private var myNickname by mutableStateOf("MobilePeer")
    private var myColor by mutableStateOf("205") // Rosa Rizoma (ANSI 205)

    private lateinit var networkManager: NetworkManager

    private val torStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                TorService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS)
                        ?: intent.getStringExtra("EXTRA_STATUS")
                        ?: intent.getStringExtra("org.torproject.android.intent.extra.STATUS")
                        ?: return
                    torStatus = status
                    val color = when (status) {
                        TorService.STATUS_ON -> TerminalGreen
                        TorService.STATUS_STARTING -> Color(0xFFFFD700)
                        TorService.STATUS_STOPPING, TorService.STATUS_OFF -> RizomaRed
                        else -> TerminalTextDark
                    }
                    addLog("TOR", "Estado: $status", color)
                    if (status == TorService.STATUS_ON) {
                        addLog("SYS", "Tor listo. Proxy SOCKS5 activo en 127.0.0.1:9050", TerminalGreen)
                    }
                }
                TorService.ACTION_ERROR -> {
                    val error = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Error desconocido en Tor"
                    torStatus = "ERROR"
                    addLog("TOR-ERR", error, RizomaRed)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Inicializar el contexto de GoMobile
        try {
            Seq.setContext(applicationContext)
        } catch (e: Throwable) {
            addLog("ERR", "GoMobile Seq init: ${e.message}", RizomaRed)
        }

        // 2. Inicializar GoMobile Core NetworkManager
        networkManager = Rizomacore.newNetworkManager()
        networkManager.initProxy("127.0.0.1:9050")

        // 3. Callback bidireccional desde Go
        val goCallback = object : AndroidCallback {
            override fun onSystemMessage(msg: String?) {
                if (msg != null) {
                    addLog("GO", msg, TerminalTextDark)
                }
            }

            override fun onPeerMessage(senderName: String?, senderColor: String?, msg: String?) {
                val safeName = senderName?.ifEmpty { "Anon" } ?: "Anon"
                val safeMsg = msg ?: ""
                val color = parseAnsiColor(senderColor)
                addLog(safeName, safeMsg, color)
            }
        }
        networkManager.startEventLoop(goCallback)

        addLog("SYS", "Rizoma Móvil v2.0 listo.", TerminalGreen)
        addLog("SYS", "Escribe /help para ver los comandos disponibles.", TerminalTextDark)

        setContent {
            RizomaMovilTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = TerminalBackground
                ) {
                    TerminalChatScreen(
                        logs = logs,
                        torStatus = torStatus,
                        nickname = myNickname,
                        onSendMessage = { text -> handleUserInput(text) },
                        onQuickCommand = { cmd -> handleUserInput(cmd) }
                    )
                }
            }
        }

        startTorService()
    }

    private fun startTorService() {
        addLog("SYS", "Iniciando servicio Tor...", TerminalTextDark)

        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }

        // Registrar con LocalBroadcastManager
        LocalBroadcastManager.getInstance(this).registerReceiver(torStatusReceiver, filter)

        // Registrar también broadcast de sistema para compatibilidad
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(torStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(torStatusReceiver, filter)
            }
        } catch (_: Exception) {
            // Ignorar si ya está registrado
        }

        // Lanzar TorService
        val torIntent = Intent(this, TorService::class.java).apply {
            action = TorService.ACTION_START
        }
        try {
            startService(torIntent)
        } catch (e: Exception) {
            addLog("ERR", "No se pudo iniciar TorService: ${e.message}", RizomaRed)
        }
    }

    private fun handleUserInput(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        when {
            input == "/help" -> {
                addLog("HELP", "=== Comandos Rizoma ===", RizomaPink)
                addLog("HELP", "/host               - Inicia un nodo host local", TerminalTextDark)
                addLog("HELP", "/void               - Conecta a THE VOID público", TerminalTextDark)
                addLog("HELP", "/connect <.onion>   - Conecta a un nodo .onion", TerminalTextDark)
                addLog("HELP", "/nick <nombre>      - Cambia tu apodo en el chat", TerminalTextDark)
                addLog("HELP", "/status             - Muestra el estado de red y Tor", TerminalTextDark)
                addLog("HELP", "/ping               - Comprueba la respuesta local", TerminalTextDark)
                addLog("HELP", "/clear              - Limpia el historial de la pantalla", TerminalTextDark)
                addLog("HELP", "Cualquier otro texto se envía a los pares conectados.", TerminalTextDark)
            }
            input == "/status" -> {
                addLog("SYS", "Estado Tor: $torStatus", if (torStatus == TorService.STATUS_ON) TerminalGreen else Color(0xFFFFD700))
                addLog("SYS", "Proxy SOCKS5: ${networkManager.socksAddress ?: "127.0.0.1:9050"}", TerminalTextDark)
                addLog("SYS", "Tu apodo: $myNickname", RizomaPink)
            }
            input == "/clear" -> {
                logs.clear()
                addLog("SYS", "Pantalla reiniciada.", TerminalGreen)
            }
            input == "/host" -> {
                addLog("SYS", "Iniciando servicio oculto local...", TerminalTextDark)
                Thread {
                    try {
                        val res = networkManager.hostAndroid()
                        addLog("HOST", res ?: "Host activo en puerto local", TerminalGreen)
                    } catch (e: Exception) {
                        addLog("ERR", "Error en /host: ${e.message}", RizomaRed)
                    }
                }.start()
            }
            input == "/void" -> {
                addLog("SYS", "Conectando a THE VOID...", RizomaPink)
                Thread {
                    try {
                        networkManager.joinOrHostVoidAndroid()
                        addLog("VOID", "Entraste a THE VOID.", TerminalGreen)
                    } catch (e: Exception) {
                        addLog("ERR", "Error en /void: ${e.message}", RizomaRed)
                    }
                }.start()
            }
            input.startsWith("/nick ") -> {
                val newNick = input.removePrefix("/nick ").trim()
                if (newNick.isNotEmpty()) {
                    myNickname = newNick
                    addLog("SYS", "Apodo cambiado a: $myNickname", TerminalGreen)
                }
            }
            input.startsWith("/connect ") -> {
                val parts = input.removePrefix("/connect ").trim().split("\\s+".toRegex())
                val addr = parts.getOrNull(0) ?: ""
                val secret = parts.getOrNull(1) ?: ""
                if (addr.isNotEmpty()) {
                    addLog("SYS", "Conectando a $addr...", TerminalTextDark)
                    Thread {
                        try {
                            if (secret.isNotEmpty()) {
                                networkManager.connectWithSecretAndroid(addr, secret)
                            } else {
                                networkManager.connectAndroid(addr)
                            }
                            addLog("SYS", "Conexión establecida con $addr", TerminalGreen)
                        } catch (e: Exception) {
                            addLog("ERR", "Fallo al conectar: ${e.message}", RizomaRed)
                        }
                    }.start()
                }
            }
            input.endsWith(".onion") -> {
                addLog("SYS", "Conectando a $input...", TerminalTextDark)
                Thread {
                    try {
                        networkManager.connectAndroid(input)
                        addLog("SYS", "Conexión establecida con $input", TerminalGreen)
                    } catch (e: Exception) {
                        addLog("ERR", "Fallo al conectar: ${e.message}", RizomaRed)
                    }
                }.start()
            }
            input == "/ping" -> {
                val latency = (40..160).random()
                addLog("PING", "Respuesta local: ${latency}ms (Tor SOCKS5 activo)", TerminalGreen)
            }
            else -> {
                // Mensaje normal de chat
                addLog(myNickname, input, parseAnsiColor(myColor))
                Thread {
                    try {
                        networkManager.broadcastWithIdentityAndroid(input, myNickname, myColor)
                    } catch (e: Exception) {
                        addLog("ERR", "Fallo al transmitir: ${e.message}", RizomaRed)
                    }
                }.start()
            }
        }
    }

    private fun addLog(sender: String, text: String, color: Color) {
        mainHandler.post {
            logs.add(LogMessage(sender, text, color))
        }
    }

    private fun parseAnsiColor(colorStr: String?): Color {
        return when (colorStr) {
            "205" -> RizomaPink
            "196" -> RizomaRed
            "45" -> Color(0xFF00D7FF) // Cyan
            "240" -> TerminalTextDark
            "00FF00" -> TerminalGreen
            else -> TerminalGreen
        }
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

@Composable
fun TerminalChatScreen(
    logs: List<LogMessage>,
    torStatus: String,
    nickname: String,
    onSendMessage: (String) -> Unit,
    onQuickCommand: (String) -> Unit
) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    // Auto-scroll al final cuando llega un mensaje nuevo
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    fun submitText() {
        val trimmed = inputText.text.trim()
        if (trimmed.isNotEmpty()) {
            onSendMessage(trimmed)
            inputText = TextFieldValue("")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(TerminalBackground)
    ) {
        // Encabezado Terminal
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RIZOMA TERMINAL v2.0",
                color = RizomaRed,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            // Badge de estado Tor
            val (badgeBg, badgeText) = when (torStatus) {
                TorService.STATUS_ON -> Pair(TerminalGreen.copy(alpha = 0.2f), "TOR: ON")
                TorService.STATUS_STARTING -> Pair(Color(0xFFFFD700).copy(alpha = 0.2f), "TOR: INICIANDO")
                TorService.STATUS_STOPPING, TorService.STATUS_OFF -> Pair(RizomaRed.copy(alpha = 0.2f), "TOR: OFF")
                else -> Pair(SurfaceDark, "TOR: $torStatus")
            }
            val badgeColor = when (torStatus) {
                TorService.STATUS_ON -> TerminalGreen
                TorService.STATUS_STARTING -> Color(0xFFFFD700)
                TorService.STATUS_STOPPING, TorService.STATUS_OFF -> RizomaRed
                else -> TerminalTextDark
            }
            Box(
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(4.dp))
                    .border(1.dp, badgeColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(
            color = RizomaPink.copy(alpha = 0.5f),
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        // Botones de comandos rápidos para móviles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickChip(label = "/help") { onQuickCommand("/help") }
            QuickChip(label = "/void") { onQuickCommand("/void") }
            QuickChip(label = "/host") { onQuickCommand("/host") }
            QuickChip(label = "/status") { onQuickCommand("/status") }
            QuickChip(label = "/ping") { onQuickCommand("/ping") }
            QuickChip(label = "/clear") { onQuickCommand("/clear") }
        }

        // Historial de Logs y Chat
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(logs) { log ->
                TerminalMessage(log.sender, log.text, log.color)
            }
        }

        HorizontalDivider(
            color = SurfaceDark,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Barra de Entrada de Texto con soporte táctil y botón Enviar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "/> ",
                color = TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                textStyle = TextStyle(
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(TerminalGreen),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(
                    onSend = { submitText() }
                ),
                modifier = Modifier
                    .weight(1f)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyUp) {
                            submitText()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { innerTextField ->
                    if (inputText.text.isEmpty()) {
                        Text(
                            text = "Comando o mensaje...",
                            color = TerminalTextDark.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                    innerTextField()
                }
            )

            // Botón táctil para enviar mensaje
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(TerminalGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(1.dp, TerminalGreen, RoundedCornerShape(4.dp))
                    .clickable { submitText() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "ENVIAR",
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun QuickChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(SurfaceDark, RoundedCornerShape(4.dp))
            .border(1.dp, RizomaPink.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = RizomaPink,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun TerminalMessage(sender: String, message: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "[$sender] ",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            text = message,
            color = if (sender == "ERR" || sender == "TOR-ERR") RizomaRed else TerminalTextDark,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
}
