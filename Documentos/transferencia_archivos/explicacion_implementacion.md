# Explicación de la Implementación: Transferencia de Archivos en Rizoma Móvil

**Fecha:** 2026-11-05  
**Rama:** `feature-filete-trans`  
**Complemento de:** `README.md` (el README es el manual y la bitácora; este documento explica **cómo funciona** por dentro).

---

## 1. La idea en una frase

Cuando mandas un archivo, la app lo **parte en pedacitos de 16 KB**, los manda uno por uno como mensajes de texto cifrados por Tor, y el otro teléfono **los vuelve a juntar**, comprueba con una huella digital (SHA-256) que llegó idéntico y solo entonces lo guarda.

Es como mandar un libro por correo en sobres numerados: el que recibe espera tener todos los sobres, los ordena, verifica que el libro esté completo y hasta entonces lo pone en el librero.

---

## 2. Las piezas y quién hace qué

La app tiene dos "mitades" que hablan entre sí:

```
┌─────────────────────────── KOTLIN (la app Android) ───────────────────────────┐
│  MainActivity.kt                    MultimediaAttachmentManager.kt            │
│  - Recibe el archivo que eligió     - Comprime imágenes y quita EXIF/GPS      │
│    el usuario                       - Saca nombre y tipo del archivo          │
│  - Muestra "Recibiendo… 45%"        - Caché de miniaturas                     │
│  - Reacciona a los avisos de Go     - Tarjetas visuales (aún sin conectar)    │
└───────────────────────────────────────┬───────────────────────────────────────┘
                                        │  llamadas y "callbacks"
                                        │  (a través de RizomaCore.aar)
┌───────────────────────────────────────▼─────────────── GO (el core) ──────────┐
│  android_wrapper.go                 file_transfer.go                          │
│  - Puerta de entrada desde Kotlin   - Parte el archivo en bloques de 16 KB    │
│  - Escucha los mensajes que llegan  - Calcula y verifica el SHA-256           │
│    y avisa a Kotlin                 - Valida y junta los bloques al recibir   │
│                                     - Guarda el archivo final                 │
│  network.go (ya existía)                                                      │
│  - Cifra cada mensaje (AES-GCM) y lo manda por Tor a los contactos conectados │
└───────────────────────────────────────────────────────────────────────────────┘
```

| Archivo | Lenguaje | Dónde vive | Papel |
| :--- | :--- | :--- | :--- |
| `file_transfer.go` | Go | `RizomaMovilApp/rizomacore/` | El "motor": partir, verificar, validar y reensamblar archivos. |
| `android_wrapper.go` | Go | `RizomaMovilApp/rizomacore/` | El "traductor" entre Go y Kotlin: funciones que Kotlin llama y avisos que Go le manda. |
| `file_transfer_test.go` | Go | `RizomaMovilApp/rizomacore/` | Pruebas automáticas del motor. |
| `RizomaCore.aar` | (compilado) | `RizomaMovilApp/app/libs/` | El core de Go empaquetado para que Android lo pueda usar. |
| `MultimediaAttachmentManager.kt` | Kotlin | `app/src/main/java/com/example/rizomamovil/` | Herramientas de multimedia del lado Android. *(Pendiente de integrar.)* |
| `MainActivity.kt` | Kotlin | `app/src/main/java/com/example/rizomamovil/` | Coordina todo en la app. *(Pendiente de integrar.)* |

### ¿Cómo se habla Kotlin con Go?

Go no corre "dentro" de Android de forma natural. La herramienta **`gomobile`** compila el código Go a una biblioteca nativa y genera clases Java que lo envuelven. Todo eso queda en **`RizomaCore.aar`**, y Android Studio lo usa como cualquier otra biblioteca (`implementation(files("libs/RizomaCore.aar"))` en `app/build.gradle.kts`).

La comunicación es en dos sentidos:

- **Kotlin → Go (llamadas):** Kotlin ejecuta métodos del `NetworkManager`, por ejemplo `networkManager.sendFileBytesAndroid(nombre, bytes)`.
- **Go → Kotlin (callbacks):** Kotlin le entrega a Go un objeto que implementa la interfaz `AndroidCallback`. Cuando pasa algo, Go llama a uno de sus 5 métodos:

| Callback | Cuándo lo llama Go |
| :--- | :--- |
| `onFileOffer(nombre, tamaño, deQuién)` | Llegó el aviso de que viene un archivo. |
| `onFileProgress(nombre, bloquesRecibidos, bloquesTotales)` | Llegó un bloque más. |
| `onFileReceived(nombre, rutaLocal, deQuién)` | El archivo está completo, verificado y guardado. |
| `onPeerMessage(nombre, color, texto)` | Un mensaje de chat normal (o el pie de foto de un archivo). |
| `onSystemMessage(texto)` | Avisos del sistema, incluidos errores ("File offer … rejected", "File transfer … failed"). |

> Regla de `gomobile`: las funciones públicas de Go solo pueden devolver **un valor y opcionalmente un error**. Por eso hubo que volver internas tres funciones que devolvían varios valores (error E8 del README).

---

## 3. Recorrido de un envío, paso a paso

```
Usuario elige archivo
      │
      ▼
[Kotlin] MainActivity.sendFile(nombre, bytes)
      │  1. Limpia el nombre (sin rutas ni "..")
      │  2. Si es imagen: BriarImageCompressor
      │       - la reduce a máx. 1000 px
      │       - la vuelve a codificar como JPEG (esto borra EXIF/GPS)
      │       - baja la calidad de 90 a 10 hasta pesar ≤ 256 KB
      │  3. Guarda una copia local en loadingbay_in/sent/
      │  4. Muestra "📎 Enviando multimedia…"
      ▼
[Go] SendFileBytesAndroid → SendFileFromBytes
      │  5. stageLocalAttachment: calcula el SHA-256 del archivo,
      │     genera un transfer_id único y cuenta los bloques
      │  6. SendAttachmentWithHeader: emite los mensajes
      │        file_offer  →  file_chunk #0 … #N-1  →  file_end
      ▼
[Go] network.go (broadcastHandler)
      │  7. Cifra cada mensaje con AES-GCM usando la llave de sesión
      │     de cada contacto y lo escribe en su conexión Tor
      ▼
   Tor (servicio oculto .onion)
```

Detalles importantes:

- **Tamaño de bloque:** 16 KB (`FileChunkSize`). Un archivo de 40 KB son 3 bloques: 16 + 16 + 8.
- **Cantidad de bloques:** `ceil(tamaño / 16 KB)`. El receptor exige que este número cuadre exactamente.
- **`transfer_id`:** primeros 16 caracteres del SHA-256 + marca de tiempo. Así, mandar el mismo archivo dos veces no produce dos transferencias con el mismo ID.
- **Doble compresión:** Kotlin ya comprimió la imagen; Go vuelve a revisarla, pero si ya es JPEG de ≤ 1000 px y ≤ 256 KB la deja intacta.
- **Límite:** no se puede enviar nada de más de 50 MB (`MaxFileTransferBytes`).
- **Existe otra entrada, `SendMediaAttachmentAndroid`,** que hace la copia local desde Go (con nombre único) y devuelve a Kotlin un JSON con los datos del adjunto. Todavía no la usa `MainActivity`, pero es la vía recomendada para corregir el error E1.

> ⚠️ **A quién le llega:** `network.go` manda cada mensaje del canal `Outgoing` a **todas** las conexiones activas, no solo al contacto del chat abierto. Esto ya pasaba con los mensajes de texto; con archivos significa que si estás conectado con varias personas, **todas reciben el archivo**. Está registrado como pendiente en el README (E10).

---

## 4. Qué viaja por la red

Cada mensaje es un **JSON en una sola línea**. Hay tres tipos principales (más `file_error` / `file_cancel` para abortar):

**1. La oferta** (anuncia el archivo, todavía sin datos):
```json
{"type":"file_offer","transfer_id":"9f2c…-18a4…","filename":"foto.jpg",
 "mime_type":"image/jpeg","file_size":40123,"total_chunks":3,
 "checksum":"9f2c… (64 caracteres hex)","width":1000,"height":750}
```

**2. Los bloques** (uno por cada 16 KB; los datos van en Base64):
```json
{"type":"file_chunk","transfer_id":"9f2c…-18a4…","filename":"foto.jpg",
 "chunk_num":0,"total_chunks":3,"data":"/9j/4AAQSkZJRgABAQ…"}
```

**3. El cierre** (repite el checksum para confirmar):
```json
{"type":"file_end","transfer_id":"9f2c…-18a4…","filename":"foto.jpg",
 "total_chunks":3,"checksum":"9f2c…"}
```

Antes de salir, `network.go` **cifra cada línea completa** con AES-GCM (la llave de sesión se acuerda en el handshake al conectarse) y la vuelve a pasar a Base64. Por eso, aunque alguien intercepte la conexión, no ve ni el nombre del archivo ni su contenido. Y como además va por Tor, tampoco sabe quién habla con quién.

**¿Cuánto pesa de más?** Base64 agrega ~33 %, y el cifrado más el segundo Base64 agregan otro tanto: un bloque de 16 KB viaja como una línea de ~29 KB.

---

## 5. Recorrido de una recepción, paso a paso

```
   Tor
    │
    ▼
[Go] network.go: descifra la línea y la publica como
     "PEER_MSG:<peerID>:<nombre>:<color>:<json>"
    │
    ▼
[Go] android_wrapper.go: StartEventLoop
    │  ¿El JSON tiene "type"? → es un mensaje de archivo
    ▼
[Go] file_transfer.go: receiveFileChunkFrom(peerID, mensaje)
    │
    ├─ file_offer  → VALIDA (tamaño, bloques, checksum, cupo)
    │                 crea el estado de la transferencia en memoria
    │                 → callback onFileOffer          → Kotlin: "📥 Recibiendo… 0%"
    │
    ├─ file_chunk  → VALIDA (índice, tamaño, que no se pase del total)
    │                 guarda el bloque (si ya lo tenía, lo ignora)
    │                 → callback onFileProgress       → Kotlin: "📥 … 66% (2/3)"
    │
    └─ file_end    → si faltan bloques: espera, sin error
                      si están todos: finaliza ↓
                      │
                      ▼
               saveFile:
                 1. escribe los bloques en orden en "foto.jpg.part"
                 2. calcula el SHA-256 mientras escribe
                 3. ¿coincide con el de la oferta?
                      sí → renombra a "foto.jpg" (o "foto_1.jpg" si ya existe)
                      no → borra el .part y avisa del error
                 → callback onFileReceived        → Kotlin: "📎 [MULTIMEDIA RECIBIDO…]"
```

### Las reglas que aplica el receptor

**Rechaza la oferta** (no guarda nada y avisa "File offer from … rejected") si:
- el tamaño es 0 o mayor de 50 MB;
- el número de bloques no es exactamente `ceil(tamaño / 16 KB)`;
- no trae un SHA-256 válido;
- ya hay 4 transferencias en curso (`MaxConcurrentTransfers`).

**Aborta la transferencia** (borra lo recibido y avisa "File transfer from … failed") si:
- llega un bloque con número fuera de rango, mal codificado, vacío o de más de 16 KB;
- los datos recibidos superan el tamaño anunciado;
- el checksum del `file_end` no coincide con el de la oferta;
- el SHA-256 final no coincide (archivo corrupto o alterado).

**Tolera** sin quejarse:
- bloques duplicados (solo cuenta el primero);
- un `file_end` que llega antes que el último bloque (espera y termina cuando llega).

**Limpia** las transferencias que pasan 30 minutos sin completarse (`TransferTimeout`), para que no se queden ocupando memoria.

### ¿Por qué tanta validación?

Porque del otro lado puede haber alguien con malas intenciones o un programa con errores. Sin estas reglas, un contacto podría:
- anunciar "0 bloques" y luego mandar bloques infinitos hasta **llenar la memoria** del teléfono;
- mandar un nombre como `../../algo` para **escribir fuera de la carpeta** de la app (lo evita `sanitizeFilename`);
- omitir el checksum para que se guarde **cualquier cosa sin verificar**;
- meter bloques en la transferencia de **otro contacto** (lo evita que la clave de cada transferencia sea `contacto + transfer_id`).

### ¿Por qué el archivo `.part`?

Para que nunca exista un archivo "a medias" con su nombre final. Mientras se escribe se llama `foto.jpg.part`; solo cuando el SHA-256 cuadra se renombra a `foto.jpg`. Si algo falla, se borra el `.part` y no queda basura.

---

## 6. Dónde quedan los archivos en el teléfono

| Qué | Ruta |
| :--- | :--- |
| Archivos recibidos | `<almacenamiento externo de la app>/loadingbay_in/` |
| Copias de lo que enviaste | `<almacenamiento externo de la app>/loadingbay_in/sent/` |

`<almacenamiento externo de la app>` es lo que devuelve `getExternalFilesDir(null)`, normalmente `/storage/emulated/0/Android/data/com.example.rizomamovil/files/`. Otras apps no pueden leer ahí en Android 11+, pero **los archivos no están cifrados** (el historial del chat sí). Es el pendiente E6 del README.

Para **abrir** un archivo con otra app (galería, lector de PDF…), Android exige un `FileProvider`, que todavía **no está configurado** en el manifest.

---

## 7. Qué ya funciona y qué falta

| Parte | Estado |
| :--- | :--- |
| Motor de Go (partir, enviar, validar, reensamblar, verificar) | ✅ Integrado, con 10 tests pasando |
| `RizomaCore.aar` recompilado | ✅ Compatible con el `MainActivity` actual |
| Compresión de imágenes en Kotlin | ⏳ Escrita, falta integrarla (requiere `ui/` y `data/`) |
| Progreso en el chat | ⏳ Escrito en `MainActivity` nuevo, falta integrarlo |
| Tarjetas visuales con miniatura | ⏳ Escritas pero **no conectadas** a `ChatScreen` |
| Abrir archivos con otras apps | ❌ Falta `FileProvider` |
| Que el usuario acepte antes de recibir | ❌ No existe; hoy se acepta automáticamente |
| Mandar solo al contacto del chat abierto | ❌ Hoy se manda a todos los conectados |
| Cifrar los archivos guardados | ❌ Se guardan en claro |

El detalle de cada pendiente (E1–E10) está en la sección 0 del `README.md`.

---

## 8. Cómo probarlo y reconstruirlo

**Correr las pruebas del motor:**
```bash
cd RizomaMovilApp/rizomacore
go test ./...
```
Prueban, entre otras cosas: envío y recepción completos con verificación, bloques duplicados, `file_end` adelantado, nombres maliciosos, checksum alterado, ofertas inválidas, bloques gigantes, aislamiento entre contactos y el límite de transferencias.

**Recompilar el AAR** después de cambiar algo en Go (ver sección 4 del README para instalar Go, `gomobile` y el NDK):
```bash
# Git Bash
export ANDROID_HOME="C:/Users/<usuario>/AppData/Local/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
cd RizomaMovilApp/rizomacore
go test ./... && gomobile bind -target=android -androidapi 24 -o ../app/libs/RizomaCore.aar .
```
Luego, en Android Studio: **File → Sync Project with Gradle Files**.

---

## 9. Glosario rápido

| Término | Qué significa aquí |
| :--- | :--- |
| **Bloque / chunk** | Pedazo de 16 KB del archivo. |
| **SHA-256 / checksum** | "Huella digital" del archivo: si cambia un solo bit, la huella cambia por completo. Sirve para saber que llegó idéntico. |
| **Base64** | Forma de escribir datos binarios usando solo letras y números, para poder meterlos en un JSON. |
| **AES-GCM** | El cifrado con el que `network.go` protege cada mensaje. Además de ocultarlo, detecta si alguien lo alteró en el camino. |
| **Tor / .onion** | Red que oculta quién se conecta con quién. Cada instancia de Rizoma tiene una dirección `.onion`. |
| **EXIF** | Metadatos que las cámaras meten en las fotos: GPS, modelo del teléfono, fecha. Se eliminan al recomprimir. |
| **gomobile / AAR** | Herramienta que convierte el código Go en una biblioteca Android (`.aar`). |
| **Callback** | Función de Kotlin que Go "llama de vuelta" para avisar que pasó algo. |
| **Path traversal** | Truco de usar `../` en un nombre de archivo para escribir fuera de la carpeta permitida. |
| **Briar** | App de mensajería sobre Tor cuyo diseño de adjuntos se tomó como referencia. |
