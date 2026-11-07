# Manual de Arquitectura e Implementación: Transferencia de Archivos y Multimedia en Rizoma Móvil

**Versión del Documento:** 1.0.0  
**Proyecto:** Rizoma Móvil (`RizomaMovilApp`)  
**Repositorios de Referencia:**  
- [Rizoma Móvil en GitHub](https://github.com/GonnSolo/rizomaMovil)  
- [Briar Core & Android en GitLab de Briar Project](https://code.briarproject.org/briar/briar)  
**Autor:** Equipo de Desarrollo y Migración Móvil  
**Fecha:** Noviembre 2026  
**Rama de integración:** `feature-filete-trans` (creada desde `main` @ `39d4416`)  
**Explicación técnica del funcionamiento:** ver [`explicacion_implementacion.md`](explicacion_implementacion.md)

---

## 0. Estado Actual de la Integración (revisión 2026-11-05)

> Esta sección se mantiene al día conforme avanza la integración. Lo que dicen las secciones 1–6 es el **diseño propuesto**; aquí se registra lo que **se verificó realmente** contra el repositorio.

### 0.1 Resumen

| Aspecto | Estado |
| :--- | :--- |
| Archivos de la entrega | 4 fuentes (`.txt`) + este README. Los **2 de Go ya están integrados** en `rizomacore/` (con correcciones, ver 0.4); los 2 de Kotlin aún **no**. |
| `main` compila por sí solo | ❌ **No.** Faltan clases y pantallas (ver 0.2). |
| Go nuevo (`file_transfer.go`, `android_wrapper.go`) | ✅ Integrado en `rizomacore/` con E2–E5, E7 (parte Go) y E8 corregidos. Compila, pasa `go vet` y **10 tests** en `rizomacore/file_transfer_test.go` (`go test ./...`). |
| `MultimediaAttachmentManager.kt` | ⚠️ No mostró errores propios al compilar, pero no es prueba completa porque el resto del proyecto no compila. |
| `MainActivity.kt` nuevo | ⚠️ No verificable hasta tener las clases faltantes. El diff contra la versión actual es limpio (solo cambios del feature + un ajuste menor en cómo se acorta la dirección `.onion`). |
| `RizomaCore.aar` | ✅ Recompilado en `app/libs/` con el core nuevo (18.7 MB, antes 13.9 MB). Expone `sendMediaAttachmentAndroid` y mantiene los 5 callbacks de `AndroidCallback`, así que el `MainActivity` actual sigue siendo compatible. |
| Entorno de compilación del core | ✅ Go 1.27.3, `gomobile`/`gobind` y NDK 30.0.16248370 instalados. `gomobile bind` del core actual funciona (~40 s). |

### 0.2 Bloqueador principal: código faltante en el repositorio
`MainActivity.kt` (tanto la versión actual como la nueva) importa clases que **no existen en el repositorio ni en ningún punto de su historial de Git** (se revisaron todas las ramas; el remoto solo tiene `main`):

- `com.example.rizomamovil.ui`: `ChatScreen`, `ContactsScreen`, `ConfigScreen`, `PasswordScreen`
- `com.example.rizomamovil.data`: `Contact`, `CryptoStorage`, `EncryptedChatMessage`

En `data/` solo existe `DataRepository.kt` (12 líneas), que no define ninguna de esas clases. Probablemente están en la computadora de quien hizo los últimos commits sin haberse subido. **Sin ellas no se puede compilar la app ni probar este feature.**

### 0.3 Diferencias entre este documento y el código entregado
1. **Las tarjetas multimedia no están conectadas.** `BriarMultimediaBubbleCard` y `BriarAttachmentStagingBar` están definidas pero no se usan en ningún lado; `attachmentsByFilename` se llena pero nadie lo lee. En la práctica el chat solo muestra **texto con la ruta del archivo**. Las secciones 1.2 y 2.2 ("Muestra card", "Abre con FileProvider") describen el objetivo, no el estado actual (ver 6.4).
2. **`FileProvider` no está configurado.** El `AndroidManifest.xml` no tiene el `<provider>` y no existe `res/xml/file_paths.xml`. Además, la ruta `<files-path path=".">` propuesta en el Paso 2 comparte **todo** el almacenamiento interno; conviene limitarla a la carpeta de adjuntos.
3. **El "bug de filtrado de contacto" (3.4) no era la causa.** En el código anterior, `targetAlias = activeContact?.alias ?: safePeer`, así que con un chat abierto la condición `activeContact?.alias == targetAlias` ya era verdadera. Quitarla casi no cambia el comportamiento. Si los archivos no aparecían en pantalla, la causa real puede seguir presente.
4. **Las pruebas de la sección 5 no se pueden reproducir.** `verify_transfer_protocol.py` no está en la entrega ni en el repo. Además, TC-01 solo cuenta llaves y paréntesis; no es una prueba de compilación.
5. **Versión de Go.** `go.mod` exige Go **1.26.3**, no 1.20+ (corregido en la sección 4).

### 0.4 Errores encontrados en el código entregado
| # | Dónde | Problema | Estado |
| :--- | :--- | :--- | :--- |
| E1 | `MainActivity.sendFile` | La copia local se guarda como `sent/<nombre>`: enviar dos archivos con el mismo nombre (p. ej. `image.jpg`) **sobrescribe** la copia del primero. | ⏳ Pendiente (Kotlin). Opción: usar `sendMediaAttachmentAndroid`, que guarda la copia con nombre único y devuelve `local_path`. |
| E2 | `android_wrapper.go` (`file_end`) | Si `file_end` llega antes del último bloque, se mostraba **"File verification failed"** aunque el archivo luego se completara bien. | ✅ Corregido: un `file_end` adelantado solo marca la transferencia y espera los bloques faltantes. |
| E3 | `android_wrapper.go` (`file_chunk`) | Si la verificación final ocurría al llegar el último bloque y fallaba, el error se **ignoraba**. | ✅ Corregido: cualquier transferencia abortada se reporta con `OnSystemMessage` ("File transfer from … failed"). |
| E4 | `ReceiveFileChunk` | El límite de 50 MB **no se aplicaba al recibir** y con `TotalChunks = 0` no había límite de bloques. Los archivos se aceptan **sin confirmación** del usuario. | ✅ Límites corregidos (ver 0.7). ⏳ La confirmación del usuario sigue pendiente: requiere un mensaje `file_accept` y UI. |
| E5 | `saveFile` | Sin `checksum`, el archivo se aceptaba **sin verificar**. | ✅ Corregido: la oferta debe traer un SHA-256 válido y `saveFile` se niega a guardar sin él. |
| E6 | Almacenamiento | Archivos enviados y recibidos quedan **sin cifrar** en el almacenamiento externo de la app (`loadingbay_in/`), aunque el chat sí esté cifrado. El mensaje del chat también guarda la ruta completa. | ⏳ Pendiente (decisión de diseño; la ruta la define Kotlin). Go ahora crea carpetas `0700` y archivos `0600`. |
| E7 | Transferencias / UI | Las transferencias usaban el **nombre** como clave (también en `attachmentsByFilename`): dos con el mismo nombre se pisaban. | ✅ Parte Go: clave = contacto + `transfer_id` único (hash + timestamp); se quitó el alias por nombre. ⏳ Parte Kotlin (`attachmentsByFilename`) pendiente. |
| E8 | `file_transfer.go` | **Impedía generar el AAR.** `ExtractImageDimensions`, `CompressImageForTor` y `StageLocalAttachment` eran públicas y devuelven varios valores; `gobind` solo admite "un valor y opcionalmente un error", así que `gomobile bind` fallaba con `functions and methods must return either zero or one values, and optionally an error`. | ✅ Corregido: renombradas a `extractImageDimensions`, `compressImageForTor` y `stageLocalAttachment` (solo se usan dentro de Go). |
| E9 | `SendFileFromBytes` | Al enviar por `SendFileBytesAndroid` (lo que usa `MainActivity`), Go **también** guardaba una copia en `sent/`, duplicando la que guarda Kotlin. | ✅ Corregido: esa ruta ya no guarda copia; solo `SendMediaAttachmentAndroid` la guarda. |
| E10 | `network.go` (`broadcastHandler`) | Cada mensaje del canal `Outgoing`, **archivos incluidos**, se manda a **todas** las conexiones activas, no solo al contacto del chat abierto. Ya pasaba con el texto; con archivos implica que todos los conectados reciben lo que envías. | ⏳ Pendiente: enviar por `SendToPeer` / `MakePeerChan` al contacto activo. Requiere decidir cómo se mapea el contacto a su `peerID`. |

> Nota: `SendFileBytesAndroid` sigue pasando por `stageLocalAttachment`, así que Go vuelve a evaluar la imagen que Kotlin ya comprimió. Si es JPEG de ≤ 1000 px y ≤ 256 KB la deja intacta (3.1, punto 5); falta comprobarlo en dispositivo.
>
> Nota: tanto la compresión de Kotlin como la de Go convierten PNG a JPEG, así que **se pierde la transparencia** (los píxeles transparentes salen negros).

### 0.5 Preparación del entorno (bitácora)
- **2026-11-05** — Se creó la rama `feature-filete-trans` desde `main`.
- **2026-11-05** — Se limpiaron cambios locales ajenos al feature: se descartaron dos líneas en blanco de `settings.gradle.kts` y se sacaron del área de preparación los archivos nuevos de `.idea/` (siguen en disco). `.idea/gradle.xml` y `.idea/misc.xml` quedan sin commitear a propósito (JDK local `temurin-17`, preferencia de cada máquina).
- **2026-11-05** — Instalado **Go 1.27.3** (`winget install GoLang.Go`).
- **2026-11-05** — Instalados `gomobile` y `gobind`, fijados a la versión de `golang.org/x/mobile` de `go.mod` (ver sección 4).
- **2026-11-05** — Verificado: el core Go actual compila; el core con los archivos nuevos compila y pasa `go vet`.
- **2026-11-05** — Instalado **Android NDK 30.0.16248370** desde el SDK Manager de Android Studio.
- **2026-11-05** — `gomobile bind` del core **actual** genera el AAR sin errores (en carpeta temporal; el AAR del repo no se ha tocado).
- **2026-11-05** — `gomobile bind` del core **nuevo** falla por E8; con el arreglo de E8 se genera correctamente.
- **2026-11-05** — Integrados `file_transfer.go` y `android_wrapper.go` en `rizomacore/` con las correcciones E2–E5, E7 (Go), E8 y E9. Formateados con `gofmt`.
- **2026-11-05** — Agregado `rizomacore/file_transfer_test.go` (10 tests, todos pasan).
- **2026-11-05** — Recompilado `app/libs/RizomaCore.aar` (y `RizomaCore-sources.jar`) con el core nuevo.
- **2026-11-05** — Commit `7a961d0` en `feature-filete-trans`: "integración core transferencia de archivos" (core Go, tests y AAR).
- **2026-11-05** — Rama publicada en GitHub: `origin/feature-filete-trans` (apunta a `7a961d0`).
- **2026-11-05** — Agregado `.kotlin/` (caché del compilador de Kotlin) a `RizomaMovilApp/.gitignore`: commit `b87aa7f` "ignorar caché de kotlin", subido a `origin/feature-filete-trans`.
- **2026-11-05** — Creada la rama `compatibilidad-mobilapc` desde `feature-filete-trans` (`b87aa7f`), sin subir.
- **2026-11-05** — Análisis Desktop ↔ Móvil contra `GonnSolo/rizomaProject` @ `612b6f9`; corregidas C1–C5 (sección 0.9) en `rizomacore/` + 1 llamada en `MainActivity.kt`; AAR recompilado (expone `initTorControl`).
- **2026-11-05** — Commit `bdadd3d` "compatibilidad desktop-móvil: hospedar con .onion, relay, whisper, .onion y archivos vacíos", subido a `origin/compatibilidad-mobilapc`. Pendiente: compilar y probar en dispositivo cuando estén `ui/` y `data/`.
- **2026-11-05** — Este README y `explicacion_implementacion.md` se agregaron al repo en `Documentos/transferencia_archivos/` (rama `compatibilidad-mobilapc`). Desde aquí, esa copia es la que se mantiene al día.

### 0.6 Próximos pasos
1. Conseguir y subir las clases faltantes (0.2) y confirmar que `main` compila.
2. ~~Instalar el NDK y recompilar `RizomaCore.aar` con los Go nuevos.~~ ✅ Hecho.
3. Copiar los archivos Kotlin, configurar `FileProvider` (con rutas acotadas) y corregir E1, E6 y E7 (parte Kotlin).
4. Conectar `BriarMultimediaBubbleCard` en `ChatScreen`.
5. Diseñar la confirmación del usuario antes de aceptar un archivo (`file_accept`), pendiente de E4.
6. Probar entre dos dispositivos reales a través de Tor, incluida la interoperabilidad con equipos que aún usan el core anterior.

### 0.7 Reglas de recepción del core (desde 2026-11-05)
El receptor ahora **rechaza** una oferta (`file_offer`) y avisa con `OnSystemMessage` ("File offer from … rejected") si:
- `file_size` es 0 o mayor que `MaxFileTransferBytes` (50 MB);
- `total_chunks` no es exactamente `ceil(file_size / 16 KB)`;
- `checksum` no es un SHA-256 hexadecimal válido;
- ya hay `MaxConcurrentTransfers` (4) transferencias en curso. Las que pasan de `TransferTimeout` (30 min) sin completarse se descartan.

Y **aborta** una transferencia en curso (aviso "File transfer from … failed") si llega un bloque con índice fuera de rango, mal codificado, vacío o mayor de 16 KB; si los datos superan el tamaño declarado; si el checksum de `file_end` no coincide con el de la oferta; o si el SHA-256 final no coincide. Nada corrupto queda en disco.

Las transferencias se identifican por **contacto + `transfer_id`**: un contacto no puede inyectar bloques en la transferencia de otro.

**Compatibilidad con el core anterior:** el emisor viejo también usa bloques de 16 KB y siempre manda checksum, así que sus envíos pasan estas validaciones (sin `transfer_id` se usa el nombre como clave, como antes). Falta confirmarlo en dispositivo.

### 0.8 Ubicación de los archivos de la entrega
| Archivo entregado | Destino en el repo | Estado |
| :--- | :--- | :--- |
| `File Transfer.go.txt` | `RizomaMovilApp/rizomacore/file_transfer.go` | ✅ Integrado y corregido |
| `Android Wrapper.go.txt` | `RizomaMovilApp/rizomacore/android_wrapper.go` | ✅ Integrado y corregido |
| — (nuevo) | `RizomaMovilApp/rizomacore/file_transfer_test.go` | ✅ Tests |
| — (generado) | `RizomaMovilApp/app/libs/RizomaCore.aar` y `RizomaCore-sources.jar` | ✅ Recompilados |
| `Multimediaattachmentmanager.kt.txt` | `RizomaMovilApp/app/src/main/java/com/example/rizomamovil/MultimediaAttachmentManager.kt` | ⏳ Espera las clases faltantes |
| `Mainactivity.kt.txt` | `RizomaMovilApp/app/src/main/java/com/example/rizomamovil/MainActivity.kt` (reemplaza) | ⏳ Espera las clases faltantes. **Al integrarlo, conservar la llamada `initTorControl(...)` de la sección 0.9.** |

### 0.9 Compatibilidad Desktop ↔ Móvil (rama `compatibilidad-mobilapc`, 2026-11-05)
Referencia: `GonnSolo/rizomaProject` @ `612b6f9` (código real de Desktop, no su README).

**Lo que ya era idéntico** (comparado línea por línea): `config.go` y `crypto_utils.go` (solo cambia `package`), handshake (`AUTH_NONE` / `AUTH_REQUIRED` → `AUTH_SECRET <sha256>` → `AUTH_OK`/`AUTH_FAIL`), derivación de llaves de sesión, cifrado AES-GCM + base64url por línea, eventos `PEER_MSG:`/`PEER_LEFT:`, `RELAY:`/broadcast, `SendToPeer`, chat JSON `{content,name,color}`, protocolo de archivos (16 KB, SHA-256, `file_offer`/`file_chunk`/`file_end`/`file_error`) y constantes del Void.

**Incompatibilidades encontradas y corregidas (todas del lado móvil):**
| # | Causa exacta | Corrección |
| :--- | :--- | :--- |
| C1 | `Host()` móvil cambió el servicio onion de `bine` por un `net.Listen("127.0.0.1:0")` y nunca publicaba nada (`_ = privKey`). Nadie podía conectarse a un móvil; si el móvil entraba primero al Void quedaba solo en un cuarto inalcanzable mientras Desktop hospedaba otro. | `tor_control.go`: cliente mínimo del control de Tor que envía el mismo `ADD_ONION ED25519-V3:<llave> Port=80,127.0.0.1:<puerto>` que `bine`. `InitTorControl(addr)` + 1 línea en `MainActivity` (socket de control de `TorService`). Sin control configurado, `Host` falla en vez de fingir. `Close()` retira el servicio. |
| C2 | Desktop como host reenvía (`RELAY`) chat y archivos a los demás; el móvil como host no reenviaba nada. | `relayFromPeer`: reenvía lo que llega de un peer entrante a los demás peers entrantes (nunca a sesiones donde el móvil es cliente). |
| C3 | El móvil ignoraba `"whisper": true`: un mensaje privado se mostraba como público (y se habría reenviado). | Campo `Whisper`; no se reenvía y llega como `[whisper] …`. |
| C4 | La UI de Desktop agrega `.onion` antes de `Connect`; el móvil no. Con el ID pelón (lo que sale de la llave de sesión compartida) Tor lo trata como hostname y falla. | `ConnectWithSecretAndroid` agrega `.onion` si falta. |
| C5 | Desktop envía y acepta archivos vacíos (`file_size 0`, `total_chunks 0`); la validación E4 los rechazaba (regresión de esta feature). | Se permite `file_size == 0` con 0 bloques. |

**Verificación:** harness que compila el código **sin modificar** de Desktop (`network.go`, `file_transfer.go`, `ui.go` → `Update`) junto con el core móvil, sobre un Tor simulado que deriva la dirección `.onion` desde la llave igual que Tor (escalar × punto base). 9 escenarios: chat ambos sentidos, secreto (incorrecto / pedido al usuario), archivos ambos sentidos (vacío, límite de bloque, imagen comprimida, `sendto`), relay vía host Desktop y vía host móvil, whisper, Void hospedado por cada lado, desconexión y reconexión, y que el texto no viaja en claro. Resultado: **9/9 con los cambios; con `main` y con `feature-filete-trans` fallan 5 escenarios por las causas C1–C5.** Además 11 tests nuevos en `rizomacore/compat_test.go`.

**No verificado:** Tor real (red Tor) ni un dispositivo Android. La línea Kotlin no presenta errores propios, pero el proyecto Android sigue sin compilar por las clases faltantes (0.2).

**Pendientes de compatibilidad:** E10 (el móvil manda todo a todas sus conexiones y `openChatWithContact` nunca cierra la anterior → mensajes cruzados entre sesiones); `bradbury_offer` se ignora (Desktop usa un PDF embebido); el móvil no espera la publicación del descriptor (Desktop sí, hasta 3 min); SOCKS fijo en `9050` (`TorService` usa `auto` si está ocupado); `HostAndroid` guarda su llave en una ruta relativa sin cifrar (dirección no estable); `onSystemMessage` en Kotlin descarta errores y avisos.

**Hallazgos en Desktop (no modificados, fuera de alcance):** el repo no compila tal cual (falta `EnsureTorBinary`); `saveFile` usa `msg.Filename` sin sanear (path traversal desde cualquier peer); el relay lanza una goroutine por mensaje (puede reordenar bloques de archivos); ambos lados crean un `bufio.Reader` para el handshake y otro para la sesión (riesgo de perder la primera línea si llega pegada).

---

## 1. Resumen Ejecutivo y Alcance del Feature

### 1.1 Contexto
Rizoma es una plataforma de mensajería cifrada basada en redes entre pares (P2P) y servicios ocultos de Tor (*Hidden Services v3*). Durante la migración de su versión original (escritorio/web) hacia dispositivos móviles Android, la transferencia de archivos permaneció como uno de los componentes críticos pendientes de adaptación.

### 1.2 Transición del Paradigma: De "Loadingbay" a Adjuntos P2P
En la versión de escritorio, el intercambio de ficheros operaba bajo el concepto de **Loadingbay**: carpetas físicas estáticas en disco (`loadingbay_out` para volcar archivos a enviar y `loadingbay_in` para recibir descargas masivas). 

Como se diagnosticó en los documentos de análisis del proyecto ([`analisisGon.md`](https://github.com/GonnSolo/rizomaMovil/blob/main/analisisGon.md) y [`opiniones.md`](https://github.com/GonnSolo/rizomaMovil/blob/main/opiniones.md)), ese modelo resulta **inviable en sistemas operativos móviles** por dos factores:
1. **Restricciones de Sandbox y Almacenamiento en Android:** A partir de Android 10+ (API 29+) con *Scoped Storage*, las aplicaciones no pueden leer ni escribir libremente en directorios arbitrarios del sistema de archivos; requieren usar el *Storage Access Framework* (SAF) y directorios internos aislados.
2. **Experiencia de Usuario (UX):** Un usuario móvil espera enviar y recibir imágenes, audios o documentos directamente dentro de la interfaz del chat, con retroalimentación visual inmediata (progreso en tiempo real, miniaturas inline y visor multimedia), no copiando archivos a carpetas del sistema.

### 1.3 El Modelo Briar como Estándar de Referencia
Para resolver este reto, se tomó como base la arquitectura de mensajería y adjuntos de **[Briar](https://code.briarproject.org/briar/briar)**. Briar es el referente en mensajería resistente a censura sobre Tor y redes malla Bluetooth/Wi-Fi. Su subsistema multimedia resuelve los desafíos técnicos de transferir datos binarios pesados sobre circuitos Tor de alta latencia y ancho de banda restringido:
* Desacoplamiento de metadatos mediante descriptores direccionados por contenido (`AttachmentHeader`).
* Eliminación obligatoria de metadatos sensibles (EXIF / GPS) y compresión iterativa de imágenes antes del envío.
* Transmisión fragmentada en bloques homogéneos de 16 KB con validación criptográfica de integridad SHA-256.
* Máquina de estados desacoplada en la interfaz (`LOADING`, `AVAILABLE`, `MISSING`, `ERROR`).

---

## 2. Arquitectura del Sistema

### 2.1 Pila Tecnológica Híbrida
Rizoma Móvil implementa una arquitectura híbrida donde el núcleo de red y criptografía corre en Go nativo compilado como biblioteca Android AAR, mientras que la interfaz gráfica y el ciclo de vida residen en Kotlin con Jetpack Compose:

```
┌────────────────────────────────────────────────────────────────────────┐
│                   CAPA ANDROID / UI (Kotlin + Compose)                │
│                                                                        │
│   MainActivity.kt ─── ChatScreen ─── BriarMultimediaBubbleCard         │
│         │                                    │                         │
│         ▼                                    ▼                         │
│   MultimediaAttachmentManager.kt  AttachmentRetrieverCache (LruCache)  │
│   (BriarImageCompressor / SAF)                                         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI / GoMobile (Seq)
┌───────────────────────────────────▼────────────────────────────────────┐
│                    CAPA NÚCLEO GO (RizomaCore.aar)                     │
│                                                                        │
│   android_wrapper.go ──────────── NetworkManager.StartEventLoop        │
│         │                                    │                         │
│         ▼                                    ▼                         │
│   file_transfer.go ────────────── Tor SOCKS5 Proxy (:9050)             │
│   (Chunking 16KB / SHA-256 / Chans)  (Onion Service v3)                │
└────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Diagrama de Secuencia de Transferencia (End-to-End)

```
[Usuario A - Emisor]              [RizomaCore A (Go)]           [Tor v3]         [RizomaCore B (Go)]          [Usuario B - Receptor]
        │                                  │                        │                     │                              │
 1. Selecciona archivo (SAF)               │                        │                     │                              │
 2. BriarImageCompressor                   │                        │                     │                              │
    - Escala a <=1000px                    │                        │                     │                              │
    - Elimina EXIF (GPS)                   │                        │                     │                              │
    - Comprime JPEG <=256KB                │                        │                     │                              │
 3. Guarda copia local en /sent/           │                        │                     │                              │
 4. sendFileBytesAndroid() ───────────────>│                        │                     │                              │
                                   5. Divide en chunks 16KB         │                     │                              │
                                      Calcula SHA-256               │                     │                              │
                                   6. Emite file_offer ────────────>│────────────> 7. ReceiveFileChunk                   │
                                                                    │                 Registra FileTransferState         │
                                                                    │              8. onFileOffer() ────────────────────>│ Muestra card:
                                   9. Emite file_chunk (0..N-1) ───>│────────────>    ReceiveFileChunk                   │ "Recibiendo... 0%"
                                      (Base64 en stream)            │                 Idempotente (len Chunks)           │
                                                                    │              10. onFileProgress() ────────────────>│ Actualiza progreso:
                                  11. Emite file_end ──────────────>│────────────>    ReceiveFileChunk                   │ "Progreso: 65%..."
                                      (Checksum SHA-256)            │                 12. Reensambla en .part            │
                                                                    │                     Valida hash SHA-256            │
                                                                    │                     Renombra a ruta final          │
                                                                    │                 13. onFileReceived() ─────────────>│ Card AVAILABLE:
                                                                    │                                                    │ "Recibido (SHA-256 ✓)"
                                                                    │                                                    │ Abre con FileProvider
```

---

## 3. Especificación Técnica de los Módulos Implementados

### 3.1 Módulo Go: `RizomaMovilApp/rizomacore/file_transfer.go`

Este archivo reemplaza la lógica legacy de carpetas y estandariza la fragmentación y verificación de integridad:

#### Estructuras Principales
```go
// Desacopla metadatos del binario (equivalente a Briar AttachmentHeader)
type AttachmentHeader struct {
    TransferID  string `json:"transfer_id"`
    Filename    string `json:"filename"`
    MimeType    string `json:"mime_type"`
    FileSize    int64  `json:"file_size"`
    TotalChunks int    `json:"total_chunks"`
    Checksum    string `json:"checksum"`
    Width       int    `json:"width,omitempty"`
    Height      int    `json:"height,omitempty"`
    Caption     string `json:"caption,omitempty"`
    LocalPath   string `json:"local_path,omitempty"`
    State       string `json:"state,omitempty"`
}

// Estructura de paquete en cable (JSON sobre socket seguro)
type FileMessage struct {
    Type        string `json:"type"`          // file_offer, file_chunk, file_end, file_error
    TransferID  string `json:"transfer_id"`
    Filename    string `json:"filename"`
    MimeType    string `json:"mime_type"`
    FileSize    int64  `json:"file_size"`
    ChunkNum    int    `json:"chunk_num"`
    TotalChunks int    `json:"total_chunks"`
    Data        string `json:"data"`          // Base64 chunk
    Checksum    string `json:"checksum"`
}
```

#### Mecanismos de Seguridad y Resiliencia
1. **Control de Concurrencia:** Todo acceso al mapa global `transfers` está resguardado por `transfersMu sync.Mutex` para evitar condiciones de carrera cuando se reciben fragmentos simultáneos.
2. **Sanitización de Nombres contra Path Traversal:**
   ```go
   func sanitizeFilename(name string) string {
       normalized := strings.ReplaceAll(name, "\\", "/")
       cleaned := filepath.Base(normalized)
       cleaned = strings.ReplaceAll(cleaned, "..", "")
       cleaned = strings.TrimSpace(cleaned)
       if cleaned == "" || cleaned == "." || cleaned == "/" {
           return fmt.Sprintf("attachment_%d.bin", time.Now().Unix())
       }
       return cleaned
   }
   ```
3. **Escritura Atómica con Verificación de Hash:**
   Los fragmentos se escriben en un archivo temporal con sufijo `.part`. Al recibir el mensaje `file_end`, se calcula el hash SHA-256 de los bytes escritos. Si coincide exactamente con el `Checksum` esperado, se ejecuta `os.Rename` atómico a la ruta definitiva. Si no coincide, el archivo temporal se elimina y la transferencia se marca como fallida.
4. **Idempotencia y Tolerancia a Desorden:**
   Los fragmentos se almacenan en un mapa `Chunks[chunkNum]`. Si un paquete llega duplicado, no corrompe el conteo. Si `file_end` llega antes del último fragmento por variaciones de latencia en Tor, la transferencia espera a que todos los fragmentos estén presentes antes de finalizar.
5. **Prevención de Doble Compresión (`CompressImageForTor`):**
   Si la imagen ya fue procesada por la capa Kotlin y cumple los parámetros de presupuesto ($\le 1000\text{ px}$ y $\le 256\text{ KB}$), Go omite la re-codificación JPEG, preservando la calidad del archivo.

---

### 3.2 Módulo Go: `RizomaMovilApp/rizomacore/android_wrapper.go`

Conecta el runtime de Go con el sistema operativo Android mediante la interfaz `AndroidCallback`:

#### Interfaz de Callback Hacia Android
```go
type AndroidCallback interface {
    OnSystemMessage(msg string)
    OnPeerMessage(senderName string, senderColor string, msg string)
    OnFileOffer(filename string, fileSize int64, fromPeer string)
    OnFileProgress(filename string, progress int64, total int64)
    OnFileReceived(filename string, localPath string, fromPeer string)
}
```

#### Métodos Exportados a JNI (`NetworkManager`)
* `SendFileBytesAndroid(filename string, data []byte) error`: Punto de entrada directo utilizado por Android para enviar archivos en memoria fragmentados en bloques de 16 KB.
* `SendMediaAttachmentAndroid(filename, mimeType, caption string, data []byte) (string, error)`: Pipeline completo de staging, compresión y emisión de encabezado BDF.
* `SetStorageDirectory(dir string)`: Configura dinámicamente la ruta de almacenamiento seguro de la app en Android.
* `StartEventLoop(cb AndroidCallback)`: Goroutine que escucha `nm.Incoming`, detecta mensajes `FileMessage` JSON y despacha los eventos hacia `cb.OnFileOffer`, `cb.OnFileProgress` y `cb.OnFileReceived`.

---

### 3.3 Módulo Kotlin: `app/.../com/example/rizomamovil/MultimediaAttachmentManager.kt`

Componente nativo Android que implementa el estándar visual y de procesamiento multimedia de Briar:

#### Componentes Clave
1. **`BriarImageCompressor`:**
   * Analiza las dimensiones de la imagen con `inJustDecodeBounds = true` para evitar picos de memoria RAM.
   * Calcula `inSampleSize` en potencias de 2 hasta situar la imagen en el límite de `MAX_ATTACHMENT_DIMENSION = 1000 px`.
   * Al decodificar a `Bitmap` y recodificar en JPEG, **elimina automáticamente todos los metadatos EXIF** (incluyendo geolocalización GPS, número de serie del sensor y fecha).
   * Bucle iterativo de compresión JPEG: reduce la calidad desde 90 hasta 10 en pasos de 10 hasta que el payload sea inferior a `MAX_COMPRESSED_IMAGE_BYTES = 256 KB`.
2. **`AttachmentRetrieverCache`:**
   * Implementa una caché de memoria `LruCache<String, ImageBitmap>` de 20 MB.
   * Evita decodificar ficheros repetidamente durante el scroll del `LazyColumn` de mensajes en Jetpack Compose.
3. **`MultimediaAttachmentHelper`:**
   * Resuelve el nombre y MIME de archivos seleccionados mediante SAF (`ContentResolver`).
   * Guarda una copia local inmediata en `loadingbay_in/sent/` para que el emisor visualice el archivo antes de que viaje por Tor.
   * Provee `openAttachmentExternally(context, item)` utilizando `FileProvider` con banderas `FLAG_GRANT_READ_URI_PERMISSION`.
4. **Componentes Compose:**
   * `BriarAttachmentStagingBar`: Barra de vista previa antes de confirmar el envío con botón para cancelar.
   * `BriarMultimediaBubbleCard`: Tarjeta dentro de la conversación que dibuja la barra de progreso reactiva en estado `LOADING` y la imagen inline o icono de archivo con sello `SHA-256 ✓` en estado `AVAILABLE`.

---

### 3.4 Módulo Kotlin: `app/.../com/example/rizomamovil/MainActivity.kt`

Actualización de la actividad principal para coordinar los eventos de transferencia:

#### Corrección del Bug de Filtrado de Contacto
En la versión anterior de `MainActivity.kt`, los métodos `onFileOffer` y `onFileReceived` contenían:
```kotlin
// ERROR PREVIO: fromPeer llega como "Host" o apodo dinámico y no coincidía con el alias guardado
if (activeContact != null && (activeContact?.alias.equals(safePeer, ignoreCase = true) || ...))
```
> ⚠️ **Revisión 2026-11-05:** este diagnóstico no se sostiene. Ver sección 0.3, punto 3.

Esto causaba que el archivo se guardara en disco pero nunca apareciera en la pantalla del usuario. La versión corregida entrega el evento directamente al chat activo (`activeContact?.alias ?: safePeer`) e indexa los adjuntos en el mapa reactivo `attachmentsByFilename`.

#### Retroalimentación de Progreso en Tiempo Real
En `onFileProgress`, el mensaje del sistema se actualiza dinámicamente en pantalla:
```kotlin
override fun onFileProgress(filename: String?, progress: Long, total: Long) {
    val safeName = MultimediaAttachmentHelper.sanitizeFilename(filename ?: return)
    val safeTotal = total.coerceAtLeast(1L)
    val pct = ((progress.toFloat() / safeTotal.toFloat()) * 100f).toInt().coerceIn(0, 100)
    mainHandler.post {
        val idx = activeChatMessages.indexOfLast {
            it.sender == "SISTEMA" && it.text.contains(safeName) && it.text.startsWith("📥")
        }
        if (idx >= 0) {
            activeChatMessages[idx] = activeChatMessages[idx].copy(
                text = "📥 Recibiendo multimedia: $safeName — $pct% ($progress/$safeTotal chunks)"
            )
        }
    }
}
```

---

## 4. Guía de Instalación y Puesta en Marcha

Para que cualquier desarrollador pueda integrar estos cambios en su copia local del repositorio `rizomaMovil`, debe seguir estos pasos en orden:

### Prerrequisitos
* Android Studio (versión Jellyfish, Koala o superior).
* Android NDK instalado (Android Studio → **SDK Manager → SDK Tools → NDK (Side by side)**). `gomobile` lo busca en `<sdk>/ndk/<versión>` o en la variable `ANDROID_NDK_HOME`.
* **Go 1.26.3 o superior** (lo exige `rizomacore/go.mod`; en Windows: `winget install GoLang.Go`).
* `gomobile` y `gobind` **en la misma versión de `golang.org/x/mobile` que fija `go.mod`** (no `@latest`, para que todos generen el mismo AAR):
  ```bash
  go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260908204917-8b95e45f8d3e
  go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260908204917-8b95e45f8d3e
  gomobile init
  ```
  Si se actualiza `golang.org/x/mobile` en `go.mod`, actualizar también esta versión.
* Dispositivo físico o emulador Android con soporte de red local para Tor.

---

### Paso 1: Copiar los Archivos en la Capa Android
1. Copiar `MultimediaAttachmentManager.kt` a:  
   `RizomaMovilApp/app/src/main/java/com/example/rizomamovil/MultimediaAttachmentManager.kt`
2. Reemplazar `MainActivity.kt` en:  
   `RizomaMovilApp/app/src/main/java/com/example/rizomamovil/MainActivity.kt`

---

### Paso 2: Configurar `FileProvider` en Android
Para permitir abrir imágenes, audios o PDFs recibidos con aplicaciones externas del sistema, verificar que `AndroidManifest.xml` contenga el proveedor de archivos:

En `RizomaMovilApp/app/src/main/AndroidManifest.xml` (dentro de la etiqueta `<application>`):
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

Crear el archivo `RizomaMovilApp/app/src/main/res/xml/file_paths.xml` si no existe:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="loadingbay" path="loadingbay_in/" />
    <files-path name="internal_files" path="." />
</paths>
```

---

### Paso 3: Actualizar y Compilar el Motor Go (`RizomaCore.aar`)
> ✅ En la rama `feature-filete-trans` este paso ya está hecho (ver 0.5 y 0.8). Después de cualquier cambio en `rizomacore/`, correr `go test ./...` antes de recompilar el AAR.

1. Reemplazar los ficheros Go en el módulo del core:
   * `RizomaMovilApp/rizomacore/file_transfer.go`
   * `RizomaMovilApp/rizomacore/android_wrapper.go`
   *(Si existe una copia espejo en `app/src/main/java/com/example/rizomamovil/rizomacore/`, sincronizarla igualmente).*
2. Abrir una terminal en el directorio `rizomacore` y compilar el binario. `gomobile` necesita saber dónde están el SDK y el NDK (ajustar la ruta y la versión del NDK a las de cada máquina):
   ```bash
   # Git Bash
   export ANDROID_HOME="C:/Users/<usuario>/AppData/Local/Android/Sdk"
   export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
   cd RizomaMovilApp/rizomacore
   gomobile bind -target=android -androidapi 24 -o ../app/libs/RizomaCore.aar .
   ```
   `-androidapi 24` coincide con `minSdk = 24` de `app/build.gradle.kts`. Además del `.aar` se genera un `RizomaCore-sources.jar`.

---

### Paso 4: Sincronización y Compilación en Android Studio
1. Abrir Android Studio y seleccionar **File → Sync Project with Gradle Files**.
2. Ejecutar **Build → Clean Project** seguido de **Build → Rebuild Project**.
3. Desplegar en el dispositivo con **Run (`Shift + F10`)**.

---

## 5. Matriz de Pruebas y Verificación

> ⚠️ **Revisión 2026-11-05:** `verify_transfer_protocol.py` no se incluyó en la entrega ni está en el repositorio, por lo que estos resultados no se han podido reproducir. Ver sección 0.3, punto 4.
>
> **Tests reproducibles (Go):** `rizomacore/file_transfer_test.go`, ejecutar con `cd RizomaMovilApp/rizomacore && go test ./...`. Cubren TC-03 a TC-07 (ida y vuelta completa con verificación SHA-256, bloques duplicados, `file_end` adelantado, path traversal, checksum alterado sin dejar basura en disco) y además: validación de ofertas, bloques sobredimensionados, aislamiento entre contactos, límite de transferencias simultáneas y que `SendFileBytesAndroid` no duplique la copia local. TC-02 (compresión de imágenes en Kotlin) sigue sin test.

La suite de verificación automatizada (`verify_transfer_protocol.py`) valida los siguientes aspectos funcionales y de seguridad:

| ID | Escenario de Prueba | Condición Probada | Resultado Verificado |
| :--- | :--- | :--- | :--- |
| **TC-01** | **Integridad Sintáctica y Delimitadores** | Balance de llaves `{}`, paréntesis `()` y corchetes `[]` en Go y Kotlin. | **PASS:** Sintaxis válida sin desbalanceos léxicos. |
| **TC-02** | **Compresión de Imágenes Briar** | Imagen 2400×1800 px ($378.6\text{ KB}$) con cabecera simulada EXIF/GPS. | **PASS:** Reducción a 1000×750 px ($46.1\text{ KB}$), EXIF eliminado al 100%. |
| **TC-03** | **Fragmentación Homogénea** | División del payload binario en chunks exactos de 16 KB. | **PASS:** 3 chunks de 16 KB generados y verificados. |
| **TC-04** | **Recepción Idempotente** | Envío de chunks duplicados y en orden aleatorio. | **PASS:** El mapa de chunks no sobre-incrementa el progreso. |
| **TC-05** | **Llegada Fuera de Orden de `file_end`** | Recepción de `file_end` antes del último fragmento por fluctuación en Tor. | **PASS:** El sistema espera a completar todos los fragmentos antes de verificar. |
| **TC-06** | **Seguridad contra Path Traversal** | Nombre de archivo malicioso: `../../secret_dir/camera_photo.jpg`. | **PASS:** Sanitizado a `camera_photo.jpg` dentro del sandbox. |
| **TC-07** | **Validación Criptográfica SHA-256** | Escritura en archivo temporal `.part` y cálculo de hash al vuelo. | **PASS:** Renombrado atómico exitoso; archivos corruptos se eliminan. |

---

## 6. Backlog de Tareas Pendientes (Roadmap Futuro)

Para los siguientes sprints de desarrollo, se recomienda abordar las siguientes mejoras incrementales sobre la base ya establecida:

### 6.1 Reanudación de Transferencias Interrumpidas (Resumable Transfers)
* **Estado Actual:** Si un circuito de Tor cae completamente durante una transferencia grande (ej. 30 MB), el receptor conserva los chunks en memoria, pero si la app se cierra, se pierde el avance.
* **Mejora:** Persistir el mapa de chunks en una tabla SQLite local (`transfer_chunks`) para solicitar únicamente los fragmentos faltantes al reconectar.

### 6.2 Soporte de Múltiples Adjuntos Simultáneos
* **Estado Actual:** La UI permite seleccionar y enviar un archivo por mensaje.
* **Mejora:** Adaptar la barra de staging para permitir selección múltiple (hasta 10 elementos, como en `MAX_ATTACHMENTS_PER_MESSAGE` de Briar), transmitiendo una lista de `AttachmentHeader` asociados a un único mensaje de chat.

### 6.3 Grabador y Reproductor de Notas de Voz Inline
* **Estado Actual:** Los archivos de audio (`.mp3`, `.ogg`, `.opus`, `.m4a`) se transmiten y abren mediante reproductores externos con `FileProvider`.
* **Mejora:** Integrar `MediaRecorder` con códec Opus en un botón de micrófono dentro de `ChatScreen` y un reproductor de audio ligero nativo de Compose con barra de reproducción.

### 6.4 Integración Visual de Burbujas en `ChatScreen.kt`
* **Estado Actual:** Los mensajes multimedia se informan mediante el formato de texto estructurado `📎 [MULTIMEDIA RECIBIDO: ...]`, con vista previa en la actividad principal.
* **Mejora:** Reemplazar el bloque `Text(msg.text)` dentro del `LazyColumn` de `ChatScreen.kt` para incrustar directamente el composable `BriarMultimediaBubbleCard(msg.attachment)`.

### 6.5 Cancelación Activa de Transferencias
* **Estado Actual:** Si el emisor cancela antes del envío, `deleteUnsentAttachments` borra la copia local.
* **Mejora:** Emitir el mensaje de control `file_cancel` en el canal de red para que el receptor descarte de inmediato el archivo temporal `.part` si el emisor cancela la subida en pleno progreso.
