# Despliegue gratuito de CuentaClara en Hugging Face Spaces + Neon (para usar en el celular)

Esta guía despliega **CuentaClara 100% funcional con costo \$0** (Java 21 + Tesseract OCR + PostgreSQL `pgvector` + Row-Level Security + Gemini Chat & Embeddings) y te permite instalarla como app en Android/iOS.

---

## Paso 1: Crear la base de datos gratuita en Neon (`pgvector`)

1. Crea una cuenta gratuita en **https://neon.tech** (no requiere tarjeta).
2. Crea un proyecto (por ejemplo `cuentaclara`, PostgreSQL 16).
3. Copia tus credenciales del dashboard de Neon:
   - Host (`ep-xxxx.us-east-2.aws.neon.tech`) → `DB_HOST`
   - Database (`neondb`) → `DB_NAME`
   - Usuario propietario (`neondb_owner`) → `DB_ADMIN_USER`
   - Password del propietario → `DB_ADMIN_PASSWORD`
4. Abre el **SQL Editor** en Neon, pega el contenido de [neon-init.sql](../docker/postgres/neon-init.sql), reemplaza `'CAMBIA_ESTA_CLAVE_SEGURA'` por una contraseña fuerte (la usarás en `DB_APP_PASSWORD`) y ejecuta el script.

> Nota: si tu base en Neon no se llama `neondb`, cambia `neondb` en la línea `GRANT CONNECT ON DATABASE ...` antes de ejecutarlo.

---

## Paso 2: Obtener API Key gratuita de Google Gemini (Chat + Embeddings)

1. Entra a **https://aistudio.google.com/apikey** con tu cuenta de Google.
2. Genera una clave (`GEMINI_API_KEY`).
3. Al configurar `AI_PROVIDER=gemini`, CuentaClara usa **Google Gemini gratis** tanto para recomendaciones/análisis (`gemini-2.5-flash`) como para los embeddings semánticos de 1536 dimensiones (`gemini-embedding-001`).

---

## Paso 3: Crear el Space en Hugging Face (Docker, 16 GB RAM gratis)

1. Crea una cuenta gratuita en **https://huggingface.co** (no requiere tarjeta).
2. Ve a **Spaces → Create new Space**:
   - **Space name**: `cuentaclara`
   - **SDK**: selecciona **Docker** (blank)
   - **Hardware**: **CPU basic · 2 vCPU · 16 GB RAM (FREE)**
   - **Visibility**: Private o Public (la aplicación ya protege todos tus datos detrás de Login JWT + RLS).
3. En el Space, abre la pestaña **Files** o clona su repositorio Git y sube este proyecto (o sincroniza el `Dockerfile` + `pom.xml` + `src/` desde tu repo de GitHub `Samuelgelvez20/CuentaClara`), asegurándote de que el `README.md` del Space incluya al principio el encabezado YAML:

```yaml
---
title: CuentaClara
emoji: 🧾
colorFrom: pink
colorTo: purple
sdk: docker
app_port: 8080
---
```

---

## Paso 4: Configurar Variables y Secrets del Space (`Settings → Variables and secrets`)

### Secrets (datos sensibles)
| Nombre | Valor |
| :--- | :--- |
| `DB_ADMIN_PASSWORD` | Contraseña de tu usuario propietario en Neon (`neondb_owner`) |
| `DB_APP_PASSWORD` | La contraseña que pusiste en `neon-init.sql` para `cuentaclara_app` |
| `JWT_SECRET` | Cadena aleatoria en Base64 de al menos 32 bytes (por ej. generada con `openssl rand -base64 48`) |
| `GEMINI_API_KEY` | Tu clave gratuita de Google AI Studio |

### Variables
| Nombre | Valor |
| :--- | :--- |
| `PORT` | `8080` |
| `DB_HOST` | `ep-xxxx.us-east-2.aws.neon.tech` *(tu host exacto de Neon)* |
| `DB_PORT` | `5432` |
| `DB_NAME` | `neondb` *(o el nombre de tu BD en Neon)* |
| `DB_SSLMODE` | `require` |
| `DB_ADMIN_USER` | `neondb_owner` *(o tu rol admin en Neon)* |
| `DB_APP_USER` | `cuentaclara_app` |
| `COOKIE_SECURE` | `true` |
| `CLAMAV_ENABLED` | `false` |
| `WORKER_CONSUMER_ENABLED` | `false` |
| `WORKER_INLINE_ASYNC_FALLBACK` | `true` |
| `AI_PROVIDER` | `gemini` |
| `GEMINI_CHAT_MODEL` | `gemini-2.5-flash` |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-001` |

### Opcional (si usas "Continuar con Google / Firebase")
- Agrega también `FIREBASE_PROJECT_ID`, `FIREBASE_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_APP_ID` y `GOOGLE_CLIENT_ID`.
- En **Firebase Console → Authentication → Settings → Authorized domains**, añade el dominio de tu Space (por ejemplo `tu-usuario-cuentaclara.hf.space`).

---

## Paso 5: Instalarla en tu celular como App

1. Abre la URL HTTPS de tu Space (por ejemplo `https://tu-usuario-cuentaclara.hf.space` o la URL directa `https://huggingface.co/spaces/...`) en **Chrome (Android)** o **Safari (iOS)**.
   - *Tip*: abre la URL directa `.hf.space` para que el manifiesto PWA cargue directo sin el marco de Hugging Face.
2. En **Android Chrome**: menú `⋮` → **"Instalar aplicación"** / **"Agregar a pantalla de inicio"**.
3. En **iPhone Safari**: botón **Compartir** → **"Agregar a pantalla de inicio"**.

CuentaClara quedará con su icono en tu celular, pantalla completa y persistencia real en Neon PostgreSQL + pgvector.
