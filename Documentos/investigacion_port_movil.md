# Investigación: Port a Móvil del Proyecto Rizoma

## 1. Análisis del Proyecto Base (Desktop)
Actualmente, Rizoma es una aplicación P2P desarrollada en **Go (Golang)** que emplea **Bubble Tea** para su interfaz de usuario (TUI - Terminal User Interface). Las capacidades principales incluyen:
- **Redes y Conectividad:** Uso de servicios Onion de la red Tor para P2P.
- **Criptografía:** Generación de claves y cifrado de la conexión.
- **Funciones:** Chat, transferencias de archivos y generación de códigos QR de manera local.

## 2. Estrategias de Implementación para Móvil (iOS y Android)

Debido a que una interfaz de terminal no es aplicable a una app móvil estándar, es imperativo separar el motor lógico del proyecto (backend) de su capa de presentación (frontend).

### Estrategia A: Frontend Híbrido + Backend en Go mediante `gomobile` (Recomendada)
Esta es la ruta más eficiente para capitalizar el código existente y ofrecer un producto de alta calidad.
- **Tecnología Frontend:** Flutter o React Native.
- **Tecnología Backend:** Se compila el código actual de Go usando `gomobile bind`. Esto genera librerías nativas (`.aar` para Android, `.framework` para iOS).
- **Flujo de Ejecución:** El frontend móvil se comunica de manera asíncrona con el core en Go a través de interfaces (bridges). Toda la gestión de Tor, criptografía y P2P permanece centralizada en el mismo código de Go, ahorrando miles de horas de desarrollo.

### Estrategia B: Framework Multiplataforma Nativo en Go
- **Tecnología:** Fyne (framework GUI para Go).
- **Ventajas:** Permite construir toda la aplicación (interfaz y lógica) utilizando exclusivamente Go, compilando para iOS y Android.
- **Desventajas:** La interfaz resultante no sigue los estándares de diseño nativos de Apple y Google (no se siente como una app móvil real) y presenta un manejo más rígido de la accesibilidad y los controles táctiles.

### Estrategia C: Reescritura Completa Nativa
- **Tecnología:** Swift para iOS y Kotlin para Android.
- **Ventajas:** Máximo rendimiento, perfecta integración con los sistemas operativos y experiencia de usuario fluida.
- **Desventajas:** Extremadamente costoso. Obliga a reescribir toda la gestión de red de Tor, P2P y criptografía de manera independiente para cada ecosistema.

## 3. Principales Retos Técnicos del Port

1. **Gestión del Ciclo de Vida de Tor (Background Execution):** 
   Ejecutar un nodo o proxy Tor requiere procesos persistentes. Android e iOS son muy agresivos cerrando procesos en segundo plano para ahorrar batería. Será crucial implementar "Foreground Services" (Android) y "Background Tasks" (iOS) para mantener las conexiones vivas o reconectar eficientemente.
2. **Sistema de Archivos y Sandboxing:**
   La transferencia y guardado de archivos de Rizoma deben adaptarse a los paradigmas de almacenamiento local de móviles. En iOS y Android no existe un acceso abierto al sistema de archivos como en un escritorio; se requiere usar los selectores de archivos nativos y almacenar en directorios aislados.
3. **Escaneo de Códigos QR:**
   El flujo actual genera el QR de forma estática en un archivo `.png`. En el móvil, se debe aprovechar la cámara del dispositivo para leer QRs en tiempo real e incluir un componente para visualizarlos nativamente en la pantalla.

## 4. Ejemplos de Proyectos Open Source (Casos de Éxito y Referencia)

A continuación, se listan proyectos de código abierto que replican las filosofías de privacidad, arquitectura P2P, y uso de Tor o Go en móviles. Su código base debe utilizarse como referencia en la fase de ingeniería.

- **[Cwtch](https://github.com/openprivacy/cwtch)**
  - *Filosofía:* Mensajero P2P descentralizado, resistente a metadatos, estructurado completamente sobre servicios Onion (Tor v3).
  - *Arquitectura Técnica:* Utiliza su motor escrito en **Go** y una interfaz de usuario cliente en **Flutter**.
  - *Relevancia para Rizoma:* Es el análogo más directo de este port. Resuelven el problema de acoplar un demonio Tor embebido en una app de Flutter (iOS/Android) usando Go para todo el trabajo pesado.

- **[Berty](https://github.com/berty/berty)**
  - *Filosofía:* App de mensajería P2P de máxima privacidad diseñada para funcionar de forma descentralizada y offline (Bluetooth/LAN).
  - *Arquitectura Técnica:* **React Native** en el frontend, conectado a un backend masivo escrito íntegramente en **Go** empaquetado con `gomobile`.
  - *Relevancia para Rizoma:* Es la referencia definitiva si la decisión técnica es usar React Native + Gomobile. Muestra cómo manejar eficientemente la comunicación asíncrona entre JS y Go, y la criptografía de alto rendimiento.

- **[Briar](https://github.com/briar/briar)**
  - *Filosofía:* Mensajería peer-to-peer cifrada (activistas/periodistas), sincronización vía Tor, Bluetooth o Wi-Fi local.
  - *Arquitectura Técnica:* Desarrollado en **Java / Kotlin**. 
  - *Relevancia para Rizoma:* Aunque no usa Go, Briar es una obra maestra en gestión de la batería y mantenimiento de conexiones Tor persistentes (Tor Background Services) en el agresivo ecosistema de Android. Su código fuente explica cómo saltar las restricciones de energía del sistema operativo.
