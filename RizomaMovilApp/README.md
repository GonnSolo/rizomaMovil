# Rizoma Móvil App (Android)

Cliente móvil nativo para Android de la red **Rizoma**, combinando la criptografía y enrutamiento P2P de Rizoma con la arquitectura móvil descentralizada sobre Tor inspirada en **Briar**.

---

## 🚀 Cómo abrir y ejecutar en Android Studio

1. **Abrir en Android Studio:**
   - En Android Studio, ve a **File > Open...** (o *Open Project*).
   - Selecciona **exactamente** la carpeta:
     ```
     rizomaMovil/RizomaMovilApp
     ```
     *(No abras la carpeta superior de todo el repositorio, debes abrir directamente `RizomaMovilApp` donde residen `settings.gradle.kts` y `build.gradle.kts`)*.

2. **Configuración del JDK:**
   - Ve a **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**.
   - Asegúrate de que **Gradle JDK** esté configurado en **Embedded JDK (jbr-17 o jbr-21)**.

3. **Sincronizar Gradle:**
   - Haz clic en el botón de sincronización de Gradle (*Sync Project with Gradle Files*).
   - Deberá sincronizar con éxito de inmediato.

4. **Ejecutar en Emulador o Dispositivo Físico:**
   - Selecciona el target de ejecución (por ejemplo `Medium_Phone_API_35` o tu teléfono Android conectado por USB con depuración habilitada).
   - Presiona **Run 'app'** (`Shift + F10` o el botón ▶️ verde).

---

## 🛠️ Arquitectura del Proyecto

El proyecto está compuesto por tres capas principales:

1. **Frontend Móvil (Jetpack Compose & Material 3):**
   - Interfaz de terminal estética monoespaciada en fondo negro y colores ANSI representativos (Rosa Rizoma `205`, Verde Terminal, Rojo `196`).
   - Soporte táctil completo: teclado virtual Android (IME Action Send), botón interactivo **ENVIAR**, y barra de comandos rápidos (`/help`, `/void`, `/host`, `/status`, `/ping`, `/clear`).
   - Monitoreo en tiempo real del estado del demonio Tor mediante BroadcastReceiver (`TorService.ACTION_STATUS`).
   - Despacho seguro de hilos hacia el hilo principal de Compose (`mainHandler.post`).

2. **Capa GoMobile (`rizomacore`):**
   - Lógica P2P, cifrado AES-GCM, derivación de llaves SHA-256 y gestión de sockets de Rizoma compilados como biblioteca nativa (`RizomaCore.aar` y `libgojni.so`).
   - Empaquetado multiplataforma para las 4 arquitecturas Android (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).
   - Para recompilar la librería Go si modificas archivos en `rizomacore/`:
     ```powershell
     cd rizomacore
     gomobile bind -target=android -o ../app/libs/RizomaCore.aar .
     ```

3. **Servicio Tor Nativo (`tor-android`):**
   - Demonio Tor embebido (`libtor.so`) que corre como servicio en Android sin necesidad de instalar Orbot o `tor.exe` externo.
   - Expone un proxy SOCKS5 local en `127.0.0.1:9050`.

---

## ⌨️ Comandos Disponibles en la Terminal Móvil

| Comando | Descripción |
| :--- | :--- |
| `/help` | Muestra la lista de comandos disponibles. |
| `/void` | Conecta automáticamente o aloja la sala compartida pública *THE VOID*. |
| `/host` | Inicia un nodo de escucha local para permitir que otros pares se conecten. |
| `/connect <.onion> [secreto]` | Establece conexión con una dirección .onion remota. |
| `/nick <nombre>` | Cambia tu apodo en la sesión de chat. |
| `/status` | Muestra el estado actual del demonio Tor, el proxy local y tu apodo. |
| `/ping` | Mide la latencia de respuesta local. |
| `/clear` | Limpia los mensajes en pantalla de la terminal. |
| *Cualquier texto* | Se envía de forma cifrada a todos los pares conectados en la red. |
| `<direccion.onion>` | Conecta directamente al nodo .onion ingresado. |

---

## 📦 Compilación desde Consola

Para generar el APK de desarrollo manualmente:
```powershell
.\gradlew.bat assembleDebug
```
El APK se genera en:
`app/build/outputs/apk/debug/app-debug.apk`
