# Briar: Aplicación de Mensajería P2P

Briar es una aplicación de mensajería P2P y de código abierto. Está diseñada para comunicación resistente a vigilancia, censura o incluso a situaciones donde Internet no funciona.

Cuando hay Internet, Briar puede utilizar Tor. Cuando no hay Internet, puede sincronizarse mediante Bluetooth o Wi-Fi entre dispositivos compatibles.

## Repositorio y Módulos

El código fuente está públicamente disponible y el repositorio tiene módulos separados para cosas como:

- briar-android
- briar-core
- briar-api
- bramble-core
- bramble-android
- briar-headless
- briar-mailbox

**Temas relacionados:**
- Criptografía
- Redes P2P
- Tor
- Android
- Java
- Administración de claves
- Bluetooth
- Wi-Fi
- Sincronización entre nodos
- Arquitectura distribuida
- Privacidad
- Pruebas de seguridad
- Git/GitHub

## Diagrama de Sincronización

```
TELÉFONO A
     │
     │ Bluetooth / Wi-Fi
     ▼
TELÉFONO B
```

## Clonar el Repositorio

```bash
git clone https://github.com/briar/briar.git
cd briar
```

## Arquitectura

```
              BRIAR
                │
        ┌───────┴───────┐
        │               │
   interfaz         funciones
   Android          de usuario
        │
        ▼
      BRAMBLE
        │
   ┌────┼─────┐
   │    │     │
  BT   Wi-Fi  Tor
```

Briar no tiene un servidor central que reciba tus mensajes. Tu teléfono es el servidor.

Cuando mandas un mensaje, Briar intenta encontrar una forma de sincronizar tu teléfono con el teléfono de tu contacto. Puede hacerlo por Internet usando Tor, por Wi-Fi local o por Bluetooth.

## 1. Generación e Identidad Criptográfica

Al crear una cuenta, la aplicación Briar genera y almacena material criptográfico directamente en el dispositivo del usuario. La cuenta queda protegida a nivel local mediante una contraseña.

A diferencia de las plataformas tradicionales, el modelo de identidad no responde a consultas centralizadas (del tipo de verificación por número telefónico ante un servidor). La identidad en Briar está estrictamente asociada a claves criptográficas, razón por la cual el proceso de añadir un contacto constituye una operación de seguridad crítica.

## 2. Autenticación Mutua y Alta de Contactos

Al agregar un contacto de manera presencial, ambos dispositivos ejecutan un proceso de autenticación:

- Cada dispositivo despliega un código QR para ser escaneado por el otro.
- A través de este escaneo mutuo, los dispositivos intercambian información que permite establecer una conexión segura y validar la identidad del dispositivo receptor.
- En el plano criptográfico, se intercambian las claves públicas y se deriva un secreto compartido, el cual posteriormente se utiliza para proteger el canal de comunicación.
- Este mecanismo con código QR evita ataques de intermediario (Man-in-the-Middle) durante la fase de alta del contacto.

## 3. Desacoplamiento de la Capa de Transporte

Briar separa explícitamente la vía física de transmisión de los mecanismos de protección de la información:

- **Vías físicas (Transportes):** Bluetooth, Wi-Fi local y la red Tor son tratados únicamente como medios de transporte.
- **Seguridad:** La capa de transporte únicamente establece la conexión; la autenticación y el cifrado residen en las capas superiores de la arquitectura.

Gracias a esta separación, la transición entre diferentes medios de transporte (por ejemplo, cambiar de una conexión Bluetooth a una Wi-Fi) no requiere reestructurar ni reinventar el sistema de seguridad subyacente.

## 4. Almacenamiento Local y Cifrado de Extremo a Extremo

Cuando se envía un mensaje, este no se remite a un servidor centralizado (como Briar.com), sino que:

- El mensaje se almacena localmente en el dispositivo emisor.
- Briar intenta sincronizar la información directamente con el dispositivo del contacto empleando cualquiera de los medios de transporte disponibles.
- El contenido se conserva de manera local sin depender de servicios en la nube.

Toda la comunicación entre dispositivos cuenta con cifrado de extremo a extremo, lo que impide que observadores de red puedan leer el contenido de los mensajes.

## 5. Función de la Red Tor

La integración de Tor no reemplaza la seguridad criptográfica preexistente en Briar. Sus funciones se dividen de la siguiente manera:

- **Cifrado:** Protege el contenido de los mensajes.
- **Red Tor:** Oculta los metadatos de la comunicación, dificultando que un observador de la red determine la relación o interacción directa entre los usuarios.

## 6. Operación Fuera de Línea y Esquema Store-and-Forward

El sistema puede operar en ausencia total de conectividad a Internet:

- Si dos usuarios se encuentran próximos físicamente, Briar sincroniza los datos mediante Bluetooth o Wi-Fi local.
- Cuando el destinatario no está presente, la red puede actuar bajo un esquema de almacenamiento y retransmisión (store-and-forward): un dispositivo intermedio puede transportar la información cifrada y sincronizarla cuando vuelva a encontrarse con el destinatario final.

Este mecanismo permite la entrega diferida sin requerir que los participantes estén conectados al mismo tiempo.

## 7. Control de Acceso y Sincronización Limitada

El modelo de retransmisión no implica que cualquier nodo o dispositivo con Briar pueda acceder al contenido:

- Briar únicamente sincroniza información con contactos previamente establecidos y autenticados.
- El intercambio de datos está condicionado a las autorizaciones del usuario según sus relaciones de contacto y los contenidos permitidos.
