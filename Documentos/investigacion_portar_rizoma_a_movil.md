# Investigación preliminar: portabilidad de Rizoma a móvil

> **Documento de avance — investigación, no implementación**
>
> Objetivo: definir **cómo podríamos llevar Rizoma a dispositivos móviles (Android/iOS)** sin empezar todavía a modificar el proyecto.

---

## 1. Objetivo

La idea no debería ser simplemente "hacer otra versión de Rizoma para celular".

Lo recomendable es analizar primero qué partes de Rizoma ya existen, cuáles pueden reutilizarse y cuáles necesitan una interfaz móvil específica.

La estrategia que se propone en esta investigación es:

```text
                 RIZOMA ACTUAL
                       │
          ┌────────────┴────────────┐
          │                         │
       Backend                   Frontend
          │                         │
          │                  ┌──────┴──────┐
          │                  │             │
          │                Web          Móvil
          │                                │
          └────────────── API ─────────────┘
```

La idea fundamental es que **el móvil no debería duplicar la lógica del sistema**.

El teléfono tendría principalmente:

- una interfaz adaptada a pantallas pequeñas;
- autenticación;
- consumo de la API;
- manejo de sesión;
- navegación móvil;
- almacenamiento local cuando sea necesario;
- acceso a funciones propias del teléfono cuando Rizoma las necesite.

La lógica importante del sistema debería mantenerse en el backend siempre que sea posible.

---

# 2. ¿Qué significa realmente "portar Rizoma"?

Hay varias formas de llevar un sistema existente a móvil.

No todas significan lo mismo.

## Opción A — Convertir la web en una PWA

Una PWA (Progressive Web App) permite que un sitio web tenga características parecidas a una aplicación.

El usuario podría abrir Rizoma desde el navegador y, dependiendo de la implementación, instalarlo en la pantalla de inicio.

### Ventajas

- Reutilización muy alta del frontend web.
- Desarrollo relativamente rápido.
- No necesariamente se necesita crear una aplicación completamente nueva.
- Puede funcionar en Android y también en iOS con ciertas limitaciones.

### Desventajas

- La experiencia no siempre se siente como una aplicación nativa.
- El acceso a funciones específicas del dispositivo puede ser más limitado.
- Hay diferencias entre Android e iOS.
- Si Rizoma necesita muchas capacidades nativas, una PWA puede quedarse corta.

### Cuándo tendría sentido

Si Rizoma es principalmente:

- formularios;
- consultas;
- dashboards;
- tablas;
- gestión de información;
- autenticación;
- CRUD;
- navegación entre pantallas;

una PWA puede ser suficiente para una primera versión móvil.

---

# 3. Opción B — Usar Capacitor

Esta opción es especialmente interesante si Rizoma ya tiene un frontend web moderno.

Capacitor permite convertir una aplicación web en una aplicación que puede ejecutarse como aplicación móvil para Android e iOS.

Capacitor está diseñado para utilizar una misma base de código web y agregar una capa de integración con APIs nativas.

Fuente oficial:

https://github.com/ionic-team/capacitor

La documentación del proyecto explica que Capacitor permite ejecutar aplicaciones web de forma nativa en iOS, Android y web, además de proporcionar APIs para acceder a capacidades del dispositivo.

## ¿Cómo funcionaría?

La estructura conceptual sería:

```text
                 RIZOMA
                    │
             Frontend Web
                    │
          ┌─────────┴─────────┐
          │                   │
       Navegador          Capacitor
                              │
                 ┌────────────┴────────────┐
                 │                         │
              Android                    iOS
                 │                         │
             APK/AAB                    IPA
```

El frontend seguiría siendo esencialmente una aplicación web.

Capacitor agregaría la capa necesaria para empaquetarlo como aplicación móvil.

## Ventajas

- Permite reutilizar bastante código web.
- Android e iOS pueden salir del mismo proyecto.
- Se pueden utilizar plugins para funciones nativas.
- Permite agregar código nativo cuando realmente sea necesario.
- Es una alternativa muy atractiva cuando ya existe un frontend web.

## Desventajas

- No todo comportamiento web se traduce automáticamente a una buena experiencia móvil.
- La interfaz debe diseñarse específicamente para móvil.
- Algunas funciones requieren plugins o código nativo.
- Hay que probar Android e iOS por separado.

---

# 4. Opción C — React Native

Otra posibilidad sería crear el cliente móvil con React Native.

React Native permite desarrollar aplicaciones móviles utilizando React y JavaScript/TypeScript, pero la interfaz móvil se construye de manera diferente a una aplicación web tradicional.

La idea sería:

```text
                 RIZOMA
                    │
             ┌──────┴──────┐
             │             │
           Web           Mobile
             │             │
          React       React Native
                           │
                    Android + iOS
```

## Ventaja importante

Si el frontend actual de Rizoma utiliza React, existe conocimiento y lógica que potencialmente puede reutilizarse.

Pero **no significa que todo el frontend pueda copiarse directamente**.

Los componentes de una aplicación web y los componentes de React Native son diferentes.

Por ejemplo:

```text
Web:
<div>
<button>
<input>

React Native:
<View>
<Pressable>
<TextInput>
```

Por eso, normalmente habría que adaptar la interfaz.

## Cuándo tendría sentido

React Native sería interesante si:

- Rizoma ya utiliza React;
- queremos una aplicación móvil más cercana a una experiencia nativa;
- esperamos utilizar bastantes funciones del teléfono;
- queremos mantener Android e iOS desde una misma base de código.

---

# 5. Opción D — Flutter

Flutter sería otra alternativa para crear el cliente móvil.

Flutter utiliza Dart y genera aplicaciones para distintas plataformas desde una misma base de código.

Documentación:

https://docs.flutter.dev/

Una diferencia importante es que Flutter no reutiliza directamente una interfaz React o HTML existente.

Por lo tanto:

```text
Rizoma Web
    │
    X
    │
No se reutiliza directamente
la interfaz
    │
    ▼
Rizoma Flutter
```

Habría que desarrollar una nueva interfaz.

## Ventajas

- Muy buen control sobre la interfaz.
- Android e iOS desde una misma base.
- Buen rendimiento.
- Excelente control de layouts.
- Adecuado para aplicaciones que necesitan una UI muy personalizada.

## Desventajas

- Mayor cantidad de trabajo si Rizoma ya tiene un frontend web.
- Hay que aprender/mantener Dart y Flutter.
- La interfaz existente no se puede trasladar directamente.

---

# 6. Comparación de las alternativas

| Alternativa | Reutilización del frontend | Android | iOS | Trabajo inicial | Acceso nativo |
|---|---:|---:|---:|---:|---:|
| PWA | Muy alta | Sí | Sí | Bajo | Medio/bajo |
| Capacitor | Muy alta | Sí | Sí | Bajo/medio | Alto |
| React Native | Media | Sí | Sí | Medio/alto | Alto |
| Flutter | Baja | Sí | Sí | Alto | Alto |
| Aplicación nativa | Muy baja | Sí | Sí | Muy alto | Máximo |

---

# 7. La alternativa que primero deberíamos investigar para Rizoma

Si Rizoma ya cuenta con un frontend web funcional, la primera alternativa que conviene estudiar seriamente es:

## **Frontend existente + Capacitor + API actual**

La razón principal es evitar reconstruir innecesariamente el proyecto.

La arquitectura podría quedar así:

```text
                    ┌───────────────────┐
                    │     RIZOMA API    │
                    │                   │
                    │  Backend          │
                    │  Base de datos    │
                    │  Autenticación    │
                    │  Lógica negocio   │
                    └─────────┬─────────┘
                              │
                            HTTPS
                              │
                ┌─────────────┴─────────────┐
                │                           │
        ┌───────▼───────┐           ┌──────▼───────┐
        │    RIZOMA WEB  │           │ RIZOMA MÓVIL │
        │                │           │              │
        │    Navegador   │           │ Capacitor    │
        └────────────────┘           └──────────────┘
```

Esto permite que web y móvil consuman el mismo backend.

---

# 8. Lo más importante: separar frontend y backend

Para portar Rizoma correctamente hay que distinguir entre:

### Frontend

Es lo que ve el usuario:

- botones;
- formularios;
- menús;
- tablas;
- pantallas;
- navegación;
- colores;
- componentes.

### Backend

Es donde se encuentra la lógica del sistema:

- autenticación;
- usuarios;
- permisos;
- reglas de negocio;
- consultas;
- procesamiento;
- acceso a la base de datos;
- API.

### Base de datos

Es donde se almacena la información.

La arquitectura ideal sería:

```text
                  ┌─────────────┐
                  │ Base datos  │
                  └──────▲──────┘
                         │
                         │
                  ┌──────┴──────┐
                  │   Backend   │
                  │             │
                  │    API      │
                  └──────▲──────┘
                         │
                    HTTPS/API
                         │
            ┌────────────┴────────────┐
            │                         │
       ┌────▼────┐              ┌────▼────┐
       │   Web   │              │  Móvil  │
       └─────────┘              └─────────┘
```

Esto es importante porque **no queremos que el celular se conecte directamente a la base de datos**.

El móvil debería comunicarse con el backend mediante una API segura.

---

# 9. ¿Cómo se comunicaría la aplicación móvil con Rizoma?

El flujo típico sería:

```text
Usuario
   │
   ▼
Abre Rizoma móvil
   │
   ▼
Inicia sesión
   │
   ▼
La aplicación envía credenciales
   │
   ▼
Backend valida
   │
   ▼
Backend devuelve sesión/token
   │
   ▼
La aplicación guarda la sesión
   │
   ▼
Usuario utiliza Rizoma
   │
   ▼
La aplicación realiza peticiones API
   │
   ▼
Backend procesa
   │
   ▼
Base de datos
   │
   ▼
Respuesta JSON
   │
   ▼
Aplicación móvil actualiza la interfaz
```

---

# 10. API como puente

La API sería el puente entre Rizoma móvil y el backend.

Por ejemplo:

```http
GET /api/usuarios
```

Podría devolver:

```json
{
  "usuarios": [
    {
      "id": 1,
      "nombre": "Usuario 1"
    },
    {
      "id": 2,
      "nombre": "Usuario 2"
    }
  ]
}
```

La aplicación móvil recibe esa información y la presenta en pantalla.

El móvil no necesita saber cómo está construida la base de datos.

---

# 11. Autenticación

Uno de los puntos que habría que revisar antes de comenzar es cómo funciona actualmente la autenticación de Rizoma.

Hay que determinar:

- ¿utiliza sesiones?
- ¿JWT?
- ¿cookies?
- ¿OAuth?
- ¿roles?
- ¿permisos?
- ¿refresh tokens?
- ¿expiración de sesión?

Para móvil, una estrategia basada en tokens suele ser más sencilla de integrar que depender de una sesión tradicional del navegador.

No significa que haya que cambiar inmediatamente el sistema.

Primero hay que analizar cómo funciona Rizoma actualmente.

---

# 12. ¿Qué partes se pueden reutilizar?

Dependiendo de la arquitectura actual, potencialmente se puede reutilizar:

### Backend

**Muchísimo.**

Idealmente:

```text
Backend
├── API
├── lógica de negocio
├── autenticación
├── permisos
└── base de datos
```

se mantiene.

### Base de datos

También debería mantenerse.

No tendría sentido crear otra base de datos únicamente porque existe una aplicación móvil.

### Frontend

Aquí depende de la tecnología.

Si se utiliza Capacitor:

```text
Frontend web
      │
      ├── reutilización
      │
      ▼
Aplicación móvil
```

La reutilización puede ser bastante alta.

Pero la interfaz deberá adaptarse a pantallas pequeñas.

---

# 13. Lo que NO deberíamos hacer

No sería recomendable:

```text
Aplicación móvil
       │
       ▼
Base de datos directamente
```

Esto puede provocar problemas graves de seguridad.

Tampoco sería recomendable duplicar toda la lógica:

```text
Backend web
   │
   └── lógica

Backend móvil
   │
   └── otra lógica
```

Eso generaría dos sistemas que tendrían que mantenerse por separado.

Lo preferible sería:

```text
                Backend único
                     │
             ┌───────┴───────┐
             │               │
            Web            Móvil
```

---

# 14. Adaptar la interfaz para celular

Aunque reutilicemos el frontend, no significa que debamos mostrar exactamente las mismas pantallas.

Una pantalla de escritorio puede tener:

```text
┌───────────────────────────────────────────────┐
│ Menú │ Dashboard                              │
├──────┼────────────────────────────────────────┤
│      │ Tabla │ Tabla │ Tabla │ Tabla │ Tabla │
│      │                                        │
│      │ Información                            │
└──────┴────────────────────────────────────────┘
```

En móvil sería más apropiado:

```text
┌──────────────────────┐
│       Rizoma         │
├──────────────────────┤
│                      │
│  Información         │
│                      │
│  ┌────────────────┐  │
│  │ Registro       │  │
│  │ Información    │  │
│  └────────────────┘  │
│                      │
├──────────────────────┤
│ Inicio │ Perfil │ +  │
└──────────────────────┘
```

Por eso la portabilidad tiene dos partes:

1. **Portabilidad técnica**
2. **Adaptación de UX/UI**

---

# 15. Navegación móvil

En escritorio es común utilizar un menú lateral.

En móvil pueden ser mejores:

- barra inferior;
- menú hamburguesa;
- navegación por pestañas;
- navegación jerárquica.

La decisión depende de las funciones reales de Rizoma.

Una posible estructura sería:

```text
Inicio
├── Dashboard
├── Módulos
├── Notificaciones
└── Perfil
```

Pero esta estructura debe definirse después de analizar las pantallas actuales de Rizoma.

---

# 16. Estado y almacenamiento local

Un dispositivo móvil puede perder conexión.

Por eso hay que decidir qué información necesita estar disponible localmente.

Ejemplo:

```text
Internet
   │
   ▼
API
   │
   ▼
Aplicación
   │
   ├── datos temporales
   ├── sesión
   └── caché
```

No todo tiene que almacenarse localmente.

Una estrategia inicial podría ser:

- sesión/token local;
- preferencias del usuario;
- información que se consulte frecuentemente;
- caché de determinadas pantallas.

La sincronización offline completa solamente tendría sentido si Rizoma realmente necesita trabajar sin conexión.

---

# 17. Seguridad

Portar Rizoma a móvil también significa revisar la seguridad.

Como mínimo habría que analizar:

### Comunicación

Usar:

```text
HTTPS
```

y no:

```text
HTTP
```

### Autenticación

Los tokens o credenciales no deberían manejarse de forma insegura.

### API

El backend debe validar:

- autenticación;
- autorización;
- parámetros;
- permisos;
- datos recibidos.

### Base de datos

La aplicación móvil nunca debería recibir credenciales de la base de datos.

---

# 18. Android e iOS

Una ventaja de Capacitor, React Native o Flutter es que podemos mantener una estrategia multiplataforma.

Conceptualmente:

```text
             Código compartido
                    │
             ┌──────┴──────┐
             │             │
          Android         iOS
             │             │
           APK/AAB        IPA
```

Sin embargo, hay diferencias entre sistemas operativos.

Hay que probar:

- tamaños de pantalla;
- teclado;
- permisos;
- navegación;
- notificaciones;
- almacenamiento;
- cámara;
- archivos;
- comportamiento en segundo plano;
- enlaces;
- inicio de sesión.

---

# 19. Publicación

Si finalmente se decide distribuir Rizoma como aplicación:

## Android

Se puede generar un paquete para Google Play.

Actualmente el formato habitual para publicar aplicaciones nuevas en Google Play es Android App Bundle (`.aab`).

## iOS

Se necesitará un proyecto de iOS y el proceso de distribución correspondiente a Apple.

Esto implica considerar:

- certificados;
- firma;
- identificador de aplicación;
- App Store Connect;
- revisión de Apple.

La publicación sería una etapa posterior.

Primero conviene conseguir una versión funcional en dispositivos reales.

---

# 20. Propuesta de fases

No conviene intentar portar todo Rizoma de una sola vez.

La estrategia recomendada sería:

## Fase 1 — Auditoría

Investigar el Rizoma actual.

Identificar:

- frontend;
- backend;
- base de datos;
- API;
- autenticación;
- módulos;
- dependencias;
- funcionalidades;
- componentes reutilizables.

**Resultado esperado:**

Un mapa técnico del sistema.

---

## Fase 2 — API

Comprobar que todas las funciones necesarias puedan utilizarse mediante API.

Por ejemplo:

```text
GET
POST
PUT/PATCH
DELETE
```

No necesariamente todos los módulos requieren todas las operaciones.

---

## Fase 3 — Diseño móvil

Seleccionar las funciones realmente necesarias en móvil.

No todo lo que existe en escritorio necesariamente tiene que aparecer de la misma forma en el celular.

Crear:

- flujo de navegación;
- wireframes;
- pantallas;
- componentes;
- estados;
- errores;
- carga.

---

## Fase 4 — Prototipo

Crear una primera versión móvil pequeña.

Por ejemplo:

```text
Login
  ↓
Inicio
  ↓
Módulo principal
  ↓
Detalle
  ↓
Editar
```

El objetivo sería comprobar que la arquitectura funciona.

---

## Fase 5 — Integración

Conectar el prototipo con la API real.

```text
Móvil
  ↓
API
  ↓
Backend
  ↓
Base de datos
```

---

## Fase 6 — Migración progresiva

Después se van agregando los demás módulos.

```text
MVP
 ↓
Módulo 1
 ↓
Módulo 2
 ↓
Módulo 3
 ↓
...
 ↓
Rizoma móvil completo
```

---

# 21. ¿Qué sería el MVP?

MVP significa **Minimum Viable Product** o Producto Mínimo Viable.

Para Rizoma no sería necesario portar absolutamente todo desde el primer día.

Un MVP podría contener:

```text
┌───────────────────────────┐
│       RIZOMA MÓVIL        │
├───────────────────────────┤
│                           │
│ Login                     │
│                           │
│ Inicio / Dashboard        │
│                           │
│ Función principal         │
│                           │
│ Perfil                    │
│                           │
└───────────────────────────┘
```

Si eso funciona correctamente, podemos continuar agregando módulos.

---

# 22. Arquitectura propuesta

Como punto de partida, la arquitectura que más sentido tendría investigar sería:

```text
                         RIZOMA
                            │
             ┌──────────────┴──────────────┐
             │                             │
          Frontend                       Backend
             │                             │
       ┌─────┴─────┐                       │
       │           │                       │
      Web        Móvil                     API
       │           │                       │
       │       Capacitor                   │
       │           │                       │
       └─────┬─────┘                       │
             │                             │
             └──────────── HTTPS ──────────┘
                                           │
                                           ▼
                                      Base de datos
```

Esto permitiría que:

- la web siga funcionando;
- el móvil utilice la misma información;
- la lógica permanezca centralizada;
- no sea necesario crear un backend independiente;
- el proyecto pueda crecer posteriormente.

---

# 23. ¿Por qué no empezar directamente con una app nativa?

Crear Android e iOS por separado significaría potencialmente:

```text
Android → Kotlin/Java
iOS     → Swift
```

y mantener dos aplicaciones.

Para un proyecto que ya existe, esto puede multiplicar el trabajo.

Si Rizoma no necesita funciones extremadamente específicas del dispositivo, una solución multiplataforma puede ser más eficiente.

---

# 24. ¿Por qué Capacitor es una opción particularmente interesante?

La principal razón es la posibilidad de aprovechar el trabajo que ya existe en web.

Capacitor está diseñado para integrarse con aplicaciones web modernas y permite agregar plataformas Android e iOS.

Además, tiene un sistema de plugins para acceder a capacidades nativas.

Ejemplo conceptual:

```text
JavaScript / TypeScript
          │
          ▼
      Capacitor
          │
    ┌─────┴─────┐
    │           │
 Android       iOS
    │           │
 Kotlin       Swift
```

Esto permite mantener una aplicación web como base y acceder a capacidades nativas cuando sea necesario.

---

# 25. ¿Qué pasa si Rizoma necesita funciones del teléfono?

Si en el futuro Rizoma necesita:

- cámara;
- GPS;
- archivos;
- notificaciones;
- biometría;
- compartir contenido;
- enlaces profundos;
- almacenamiento local;

habría que revisar si existe un plugin adecuado o si hace falta desarrollar una integración nativa.

Capacitor proporciona una API de plugins precisamente para este tipo de integración.

---

# 26. React Native vs Capacitor para Rizoma

Si Rizoma ya tiene un frontend web funcional, la comparación sería aproximadamente:

| Punto | Capacitor | React Native |
|---|---|---|
| Reutilizar UI web | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| Reutilizar lógica JS/TS | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Crear UI móvil específica | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Integración nativa | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Trabajo inicial | Menor | Mayor |
| Mantener web + móvil | Muy conveniente | Requiere separar UIs |
| Ideal para sistema web existente | Sí | Depende |

Por eso, **antes de reescribir Rizoma en React Native o Flutter, conviene probar si Capacitor cubre las necesidades reales del proyecto.**

---

# 27. Qué necesitamos revisar del Rizoma actual

Antes de tomar una decisión definitiva, hay que hacer una auditoría del repositorio.

### Frontend

Identificar:

- framework;
- versión;
- estructura;
- componentes;
- routing;
- manejo de estado;
- llamadas HTTP;
- autenticación;
- diseño responsive.

### Backend

Identificar:

- framework;
- endpoints;
- autenticación;
- autorización;
- estructura de respuestas;
- errores;
- CORS;
- archivos;
- sesiones/tokens.

### Base de datos

Identificar:

- motor;
- modelos;
- relaciones;
- migraciones;
- datos que necesita cada módulo.

### Infraestructura

Identificar:

- Docker;
- variables de entorno;
- dominio;
- HTTPS;
- servidor;
- CI/CD;
- configuración de producción.

---

# 28. Checklist de auditoría

Antes de comenzar la implementación:

- [ ] Identificar framework frontend.
- [ ] Identificar framework backend.
- [ ] Identificar base de datos.
- [ ] Revisar endpoints.
- [ ] Revisar autenticación.
- [ ] Revisar autorización.
- [ ] Revisar CORS.
- [ ] Revisar diseño responsive.
- [ ] Identificar módulos principales.
- [ ] Identificar funciones exclusivas de escritorio.
- [ ] Identificar funciones que necesitan hardware móvil.
- [ ] Revisar almacenamiento local.
- [ ] Revisar manejo de archivos.
- [ ] Revisar notificaciones.
- [ ] Revisar dependencias incompatibles con móvil.
- [ ] Seleccionar MVP.
- [ ] Probar Capacitor como primera alternativa.
- [ ] Comparar con React Native solamente si aparecen limitaciones.

---

# 29. Riesgos principales

## Riesgo 1 — La interfaz actual no es responsive

Solución:

Adaptar el diseño antes o durante la integración móvil.

---

## Riesgo 2 — La autenticación depende demasiado del navegador

Solución:

Revisar el sistema actual de sesión y diseñar una estrategia compatible con móvil.

---

## Riesgo 3 — Funciones que solamente funcionan en escritorio

Solución:

Identificarlas durante la auditoría y definir una alternativa móvil.

---

## Riesgo 4 — Dependencias incompatibles

Algunas librerías web pueden depender de:

- APIs específicas del navegador;
- ventanas;
- tamaños de pantalla;
- mouse;
- hover;
- teclado físico.

Estas partes tendrían que adaptarse.

---

## Riesgo 5 — Querer migrar todo al mismo tiempo

Esto aumenta mucho la probabilidad de errores.

La estrategia recomendada es:

```text
Auditoría
   ↓
Prototipo
   ↓
MVP
   ↓
Pruebas
   ↓
Migración progresiva
```

---

# 30. Conclusión preliminar

La investigación apunta a que **no deberíamos comenzar haciendo Rizoma nuevamente desde cero**.

Primero debemos estudiar cuánto del proyecto actual puede reutilizarse.

La arquitectura objetivo más interesante para investigar es:

```text
                  RIZOMA
                     │
          ┌──────────┴──────────┐
          │                     │
       Web actual          App móvil
                                │
                            Capacitor
                                │
                         Android + iOS
                                │
                                │
                         misma API
                                │
                                ▼
                            Backend
                                │
                                ▼
                           Base de datos
```

La ventaja principal es que el backend y la base de datos pueden continuar siendo el centro del sistema.

La aplicación móvil sería principalmente un nuevo cliente de Rizoma.

### Decisión preliminar

**Primera opción a evaluar:**

> **Frontend actual + Capacitor + API existente**

**Segunda opción a evaluar si Capacitor presenta limitaciones importantes:**

> **React Native**

**Tercera opción, si se justifica una reconstrucción completa de la interfaz:**

> **Flutter**

**Aplicación nativa Android/iOS desde cero:**

> Solo tendría sentido si Rizoma necesita capacidades específicas que justifiquen mantener dos clientes nativos.

---

# 31. Siguiente paso recomendado

Esta investigación todavía **no debería convertirse en implementación**.

El siguiente paso técnico debería ser realizar una **auditoría real del repositorio de Rizoma**.

Necesitamos revisar el código para responder:

1. ¿Con qué tecnología está hecho el frontend?
2. ¿Con qué tecnología está hecho el backend?
3. ¿Ya existe una API?
4. ¿Cómo funciona actualmente el login?
5. ¿Cómo se manejan las sesiones?
6. ¿Qué módulos tiene Rizoma?
7. ¿Qué librerías utiliza?
8. ¿Qué partes del frontend pueden reutilizarse?
9. ¿Qué partes no son compatibles con móvil?
10. ¿Capacitor puede cubrir el 80–90 % de las necesidades?
11. ¿Qué funciones necesitarían plugins nativos?
12. ¿Cuál debería ser el MVP móvil?

**Hasta responder esas preguntas no conviene elegir definitivamente la tecnología.**

---

# Fuentes consultadas

1. Android Developers — Guía de arquitectura de aplicaciones  
   https://developer.android.com/topic/architecture

2. Capacitor — Repositorio oficial  
   https://github.com/ionic-team/capacitor

3. Capacitor — README oficial  
   https://github.com/ionic-team/capacitor/blob/main/README.md

4. Flutter — Documentación oficial  
   https://docs.flutter.dev/

5. GitHub — Organización pública relacionada con Rizoma encontrada durante la investigación  
   https://github.com/rizoma

> **Nota:** La organización pública `rizoma` encontrada en GitHub no se tomó como el código fuente de nuestro proyecto. La búsqueda únicamente sirve como referencia externa; para decidir la estrategia real hay que auditar el repositorio de Rizoma que estamos desarrollando.
