# Análisis del Backend de Briar

Briar es una aplicación de mensajería enfocada en la privacidad, que opera de forma descentralizada y **peer-to-peer (P2P)**. Por lo tanto, **no posee un "backend" tradicional o centralizado** (como servidores en AWS o Google Cloud que procesan o almacenan mensajes). En lugar de ello, cada cliente de Briar (tu teléfono) actúa como su propio servidor y cliente simultáneamente.

El "backend" lógico (las capas de red, criptografía, almacenamiento y sincronización) está integrado directamente en la aplicación a través de dos sistemas modulares principales: **Bramble** y **Briar Core**.

---

## 1. El Framework Subyacente: Bramble
Bramble es la capa de transporte y enrutamiento P2P de Briar. Toda la lógica compleja de conectividad y sincronización de estado está separada aquí, de manera que la aplicación no se ocupa de cómo se envían los datos, sino qué datos enviar.

*   **Sincronización P2P y Transporte Híbrido:** Bramble sincroniza datos de forma autónoma. No depende de una sola red.
    *   **Tor (Onion Routing):** Si hay acceso a Internet, utiliza Tor ocultando los metadatos y conectando nodos de forma segura y anónima (`onionwrapper-core`).
    *   **Wi-Fi / Bluetooth:** Si no hay Internet (por censura o caídas de red), Bramble se conecta directamente con dispositivos cercanos para sincronizar la base de datos de mensajes, permitiendo comunicación en contextos de crisis.
*   **Criptografía y Seguridad:** Utiliza cifrado de extremo a extremo por defecto y permanentemente. Emplea librerías como BouncyCastle, protocolos Curve25519 y EdDSA para firma y establecimiento de conexiones seguras.
*   **Base de Datos Local:** Como no hay un servidor backend, todos los datos residen de manera local y cifrada (utilizando H2 en entornos Java puros, o SQLite en Android).

## 2. La Lógica de Aplicación: Briar Core
Sobre la infraestructura de Bramble se construye `briar-core`, el módulo que conoce las "reglas de negocio" de la aplicación.
*   **Mensajes y Foros:** Se encarga de gestionar la entrega eventual (Eventual Consistency) en la mensajería 1-a-1, foros y grupos privados distribuidos.
*   **Fuentes RSS (Blogs):** Permite descargar y compartir feeds (usando herramientas como `rome` y `jsoup`) propagándolos entre los contactos a través del protocolo P2P.
*   **Inyección de Dependencias:** Todo el backend está organizado bajo inyección de dependencias estricta utilizando **Dagger**.

## 3. Extensiones: Mailbox y Headless
Aunque no exista un backend central para la app global, Briar ofrece componentes para usuarios avanzados que desean tener un servidor personal:

*   **Briar Headless:** Es la versión de Briar en formato consola (sin interfaz gráfica de usuario). Se utiliza normalmente para bots, scripts, o como un nodo en un servidor para asegurar alta disponibilidad.
*   **Briar Mailbox:** Actúa como un servidor "buzón" personal. Puedes instalarlo en un dispositivo Android viejo conectado en casa (o en una Raspberry Pi). Su función es almacenar los mensajes cifrados que te envían mientras tú (tu celular principal) estás fuera de línea (sin batería o sin red), para entregártelos en cuanto te conectes. El Mailbox es _trustless_ (no puede descifrar los mensajes, solo los guarda).

## 4. Stack Tecnológico
Si revisas los repositorios y archivos `build.gradle`, el "backend" P2P se compone principalmente de:
*   **Lenguajes:** Java y Kotlin.
*   **Networking:** OkHttp, y librerías nativas para control de Tor, Bluetooth y sockets.
*   **Arquitectura:** Diseño altamente modularizado (separando API e implementación, ej: `briar-api` y `briar-core`).

---

## 5. Diferencias Clave entre Briar y Rizoma

Mientras que ambos proyectos comparten la filosofía de privacidad descentralizada, carencia de servidores centrales y el uso de la red Tor para el enrutamiento seguro, difieren significativamente en arquitectura y experiencia de usuario:

*   **Integración de Tor:** 
    *   **Briar** incrusta Tor nativamente en la aplicación (usando wrappers de C a Java/Kotlin). El usuario no necesita descargar nada extra; Tor se inicia silenciosamente y su ciclo de vida se gestiona automáticamente.
    *   **Rizoma** requiere (por ahora) que el usuario descargue y configure manualmente el Tor Expert Bundle (`tor.exe`) en la misma carpeta o compilarlo en un .zip unificado, tratando a Tor como un proceso externo manejado por comandos de sistema.
*   **Modelo de Estado y Mensajería:**
    *   **Briar** usa un modelo asíncrono de consistencia eventual con almacenamiento persistente. Si estás desconectado, los mensajes se encolan y se envían automáticamente al recuperar la conexión o a través de contactos intermedios.
    *   **Rizoma** tiene un enfoque de sesión síncrono y efímero ("The Void", "Loading Bay"). Debes *hostear* o unirte a una sesión activa en tiempo real. 
*   **Redes Alternativas (Transportes Híbridos):**
    *   **Briar** no solo usa Tor; conmuta automáticamente a Bluetooth o Wi-Fi Direct/LAN si estás cerca del contacto, funcionando sin Internet (útil en cortes masivos).
    *   **Rizoma** depende 100% de la red Tor para el transporte de red, incluso si ambas computadoras están en la misma habitación.
*   **Enfoque de Plataforma:** Briar es principalmente móvil diseñado para consumo bajo de batería (Android), mientras que Rizoma es una aplicación de escritorio/CLI (Go) muy potente pero que asume alimentación constante.

---

## 6. Preguntas y Respuestas (Q&A)

### 1. ¿Cómo resolvió Briar el problema de tener que "precalentar" el servidor de Tor para hacer la mensajería responsiva?
El problema de "precalentar" (el tiempo que tarda Tor en publicar un *Hidden Service* y establecer circuitos de red) Briar lo resuelve de tres maneras clave:
*   **Conexiones Persistentes (Multiplexadas):** En lugar de abrir una conexión Tor nueva para cada mensaje o acción (lo cual provoca latencia masiva cada vez), Briar abre una única conexión de larga duración (*stream*) de forma temprana. Todos los mensajes, chats y foros viajan multiplexados por ese mismo "tubo", haciendo que, una vez conectados, los mensajes se sientan instantáneos.
*   **Servicios en Segundo Plano:** La app mantiene su *Hidden Service* publicado silenciosamente en segundo plano, por lo que el "precalentamiento" ocurre antes de que el usuario necesite usar la app.
*   **Uso del Mailbox para Asincronía:** Si el precalentamiento tarda o un nodo no está listo, el mensaje va al Mailbox. Esto oculta la latencia de la red al usuario, que ve su mensaje como "Enviado", mientras la app se pelea con los tiempos de Tor internamente.

### 2. ¿Qué partes podemos tomar de Briar para agregarlo a Rizoma y hacer el proyecto mucho mejor?
*   **Tor Embebido Nativo:** Importar una librería de Go que incruste Tor (como `bine` de creshaw) en lugar de depender de `tor.exe` externo. Haría que el ejecutable de Rizoma fuera realmente *standalone* y eliminaría todo el setup manual.
*   **Sincronización LAN Local (Zero-Config):** Añadir soporte para mDNS/UDP local. Si Rizoma detecta que la IP remota o un usuario activo está en tu misma red LAN (como en la misma oficina), mandar los archivos por conexión directa a la IP saltándose Tor. Esto pasaría transferencias lentas a velocidad Gigabit al instante.
*   **Protocolo Multiplexador para *The Void*:** Implementar librerías como *Yamux* o *QUIC* en las conexiones. Así, mantienes un socket Tor abierto permanente donde la CLI responde de forma instantánea sin la lentitud inicial para cada comando.
*   **El Concepto de Buzón (Mailbox Node):** Habilitar una bandera en la CLI (`rizoma --mailbox`) que permita dejar un PC prendido que actúe de relevo ciego, almacenando archivos y mensajes temporales encriptados de otros amigos para entregarlos luego, creando una red mucho más asíncrona y tolerante a que tus amigos apaguen su PC.

### Otras preguntas interesantes sobre arquitectura distribuida:

### 3. ¿Cómo evita Briar el consumo excesivo de batería en el móvil manteniendo Tor corriendo todo el tiempo?
Este era un gran reto, pues la conexión persistente (el polling) devora batería. Briar lo resolvió separando notificaciones y entrega de red. Utiliza conexiones muy ligeras hacia su propio Mailbox o permite notificaciones push truncadas, que funcionan solo como "Toques". El móvil enciende su motor de red pesado (Tor) únicamente cuando se le avisa (mediante un token ciego que no revela la identidad del remitente) que hay un mensaje pendiente esperándolo en el buzón. 

### 4. ¿De qué manera manejan transferencias de archivos grandes si la conexión de Tor se interrumpe constantemente?
Briar (y su protocolo Bramble) trata todos los datos como "bloques o eventos" agregados con hashes criptográficos. Nunca envían un archivo lineal asumiendo que la conexión durará. Si la red Tor se cae al 60% del archivo, el receptor tiene los bloques guardados. Al reconectarse (incluso por otra red como LAN), cruzan hashes para saber dónde quedaron y retoman instantáneamente, siendo extremadamente resiliente a cortes. Rizoma podría implementar *chunking* (segmentación) para el *Loading Bay*, haciendo que la transferencia de archivos grandes sobre Tor no se frustre por fallos de red.
