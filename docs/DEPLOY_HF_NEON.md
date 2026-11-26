# Despliegue gratuito de CuentaClara en Render.com + Neon (para usar en el celular)

> **Nota sobre Hugging Face Spaces:** Hugging Face movió recientemente los Spaces con **SDK Docker** a plan pago (**PRO**). Para desplegar el backend Docker de CuentaClara **100% gratis y sin tarjeta**, la mejor alternativa actual conectada directo a GitHub es **Render.com (Free Web Service)** junto con **Neon.tech (PostgreSQL + pgvector)**.

Esta guía despliega **CuentaClara 100% funcional con costo \$0** (Java 21 + Tesseract OCR + PostgreSQL `pgvector` + Row-Level Security + Gemini Chat & Embeddings) y te permite instalarla como app en Android/iOS.

---

## Paso 1: Crear y preparar la base de datos gratuita en Neon (`pgvector`)

1. Crea una cuenta gratuita en **https://neon.tech** (sin tarjeta).
2. Crea un proyecto (por ejemplo `CuentaClara`, PostgreSQL **16**, región cercana como US East).
3. Abre el **SQL Editor** en Neon, pega el contenido de [neon-init.sql](../docker/postgres/neon-init.sql), reemplaza `'CAMBIA_ESTA_CLAVE_SEGURA'` en ambas líneas por una contraseña fuerte de 16+ caracteres (con mayúsculas, minúsculas, números y símbolos, sin `'`), y presiona **Run**.
4. Ve a **Dashboard → Connect**, **desmarca "Connection pooling"** *(usa la conexión directa sin `-pooler` para que Flyway y el RLS funcionen sin bloqueos)* y anota tus datos:
   - Host (`ep-xxxx.aws.neon.tech`) → `DB_HOST`
   - Database (`neondb`) → `DB_NAME`
   - Role (`neondb_owner`) → `DB_ADMIN_USER`
   - Password → `DB_ADMIN_PASSWORD`

---

## Paso 2: Obtener API Key gratuita de Google Gemini (Chat + Embeddings)

1. Entra a **https://aistudio.google.com/apikey** con tu cuenta de Google.
2. Haz clic en **Create API key** (`GEMINI_API_KEY`).
3. Con `AI_PROVIDER=gemini`, CuentaClara usa **Google Gemini gratis** tanto para análisis/recomendaciones (`gemini-2.5-flash`) como para embeddings semánticos de 1536 dimensiones (`gemini-embedding-001`).

---

## Paso 3: Crear el Web Service gratuito en Render.com (Docker, sin tarjeta)

1. Entra a **https://dashboard.render.com** e inicia sesión con tu cuenta de **GitHub** (no requiere tarjeta para Free Web Services).
2. Haz clic en **New + → Web Service**.
3. Conecta tu repositorio **`Samuelgelvez20/CuentaClara`** (rama **`main`**).
4. Configura el servicio así:
   - **Name:** `cuentaclara`
   - **Language / Runtime:** **`Docker`** (detecta automáticamente el [Dockerfile](../Dockerfile))
   - **Region:** **Virginia (`US East`)** *(la más cercana a Neon US East y Colombia)*
   - **Instance Type:** **Free (`512 MB RAM / 0.1 CPU`)**
   - **Health Check Path (en Advanced):** `/`

> **Limitación del plan Free de Render:** tras 15 minutos sin tráfico el servicio se suspende y tarda ~45–60 s en despertar al abrirlo desde el celular. El límite de 512 MB RAM queda cubierto con los flags de JVM incluidos abajo (`JAVA_OPTS`) y en el `Dockerfile`.

---

## Paso 4: Configurar Environment Variables en Render (`Environment`)

Agrega estas variables antes de desplegar:

| Variable | Valor |
| :--- | :--- |
| `PORT` | `8080` |
| `JAVA_OPTS` | `-Xms96m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k` |
| `DB_HOST` | *Tu host directo de Neon sin `-pooler`* (ej. `ep-xxxx.aws.neon.tech`) |
| `DB_PORT` | `5432` |
| `DB_NAME` | `neondb` |
| `DB_SSLMODE` | `require` |
| `DB_ADMIN_USER` | `neondb_owner` |
| `DB_ADMIN_PASSWORD` | *La contraseña de `neondb_owner` en Neon* |
| `DB_APP_USER` | `cuentaclara_app` |
| `DB_APP_PASSWORD` | *La contraseña fuerte que pusiste en `neon-init.sql`* |
| `JWT_SECRET` | *Cadena aleatoria Base64 de 32+ bytes* (genera una con `openssl rand -base64 48`) |
| `COOKIE_SECURE` | `true` |
| `CLAMAV_ENABLED` | `false` |
| `WORKER_CONSUMER_ENABLED` | `false` |
| `WORKER_INLINE_ASYNC_FALLBACK` | `true` |
| `AI_PROVIDER` | `gemini` |
| `GEMINI_API_KEY` | *Tu clave de Google AI Studio* |
| `GEMINI_CHAT_MODEL` | `gemini-2.5-flash` |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-001` |

### Opcional (si usas "Continuar con Google / Firebase")
- Agrega también `FIREBASE_PROJECT_ID`, `FIREBASE_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_APP_ID` y `GOOGLE_CLIENT_ID`.
- En **Firebase Console → Authentication → Settings → Authorized domains**, añade el dominio que te asigne Render (por ejemplo `cuentaclara.onrender.com`).

Haz clic en **Deploy Web Service** y espera unos minutos mientras compila el `Dockerfile` con Maven + Java 21 + Tesseract OCR.

---

## Paso 5: Instalarla en tu celular como App (PWA)

1. Abre tu URL HTTPS de Render (`https://cuentaclara.onrender.com` o la que te asigne) en **Chrome (Android)** o **Safari (iOS)**.
2. Inicia sesión o regístrate.
3. En **Android Chrome**: menú `⋮` → **"Instalar aplicación"** / **"Agregar a pantalla de inicio"**.
4. En **iPhone Safari**: botón **Compartir** → **"Agregar a pantalla de inicio"**.

CuentaClara quedará instalada en tu celular con icono propio, interfaz adaptable móvil y persistencia segura en Neon PostgreSQL + pgvector.
