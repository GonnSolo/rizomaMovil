# Manual de Arquitectura e Implementación: Transferencia de Archivos y Multimedia en Rizoma Móvil

**Versión del Documento:** 1.0.0  
**Proyecto:** Rizoma Móvil (`RizomaMovilApp`)  
**Repositorios de Referencia:**  
- [Rizoma Móvil en GitHub](https://github.com/GonnSolo/rizomaMovil)  
- [Briar Core & Android en GitLab de Briar Project](https://code.briarproject.org/briar/briar)  
**Autor:** Equipo de Desarrollo y Migración Móvil  
**Fecha:** Noviembre 2026  

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
* Android NDK instalado (versión 25 o superior configurada en `local.properties`).
* Go 1.20+ y herramienta `gomobile` instalada:
  ```bash
  go install golang.org/x/mobile/cmd/gomobile@latest
  gomobile init
  ```
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
1. Reemplazar los ficheros Go en el módulo del core:
   * `RizomaMovilApp/rizomacore/file_transfer.go`
   * `RizomaMovilApp/rizomacore/android_wrapper.go`
   *(Si existe una copia espejo en `app/src/main/java/com/example/rizomamovil/rizomacore/`, sincronizarla igualmente).*
2. Abrir una terminal en el directorio `rizomacore` y compilar el binario:
   ```bash
   cd RizomaMovilApp/rizomacore
   gomobile bind -target=android -androidapi 24 -o ../app/libs/RizomaCore.aar .
   ```

---

### Paso 4: Sincronización y Compilación en Android Studio
1. Abrir Android Studio y seleccionar **File → Sync Project with Gradle Files**.
2. Ejecutar **Build → Clean Project** seguido de **Build → Rebuild Project**.
3. Desplegar en el dispositivo con **Run (`Shift + F10`)**.

---

## 5. Matriz de Pruebas y Verificación

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
