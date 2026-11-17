# CuentaClara — Finanzas Personales con RAG sobre PostgreSQL + pgvector

**CuentaClara** es una aplicación web construida con **Java 21** y **Spring Boot 3.4** que convierte las facturas electrónicas (XML UBL 2.1 DIAN, PDF digital o foto con OCR), extractos bancarios PDF y gastos diarios del usuario en un historial consultable en lenguaje natural con **citas verificables** y ofrece planificación financiera en dos etapas:
1. **Desde el Día 1:** Cuestionario financiero inicial que genera un **Presupuesto Inicial Estimado** bajo la regla **50/30/20** (totalmente personalizable sumando `100 %`).
2. **Al cierre del primer periodo:** Informe comparativo en SQL entre lo declarado y los movimientos confirmados, con recomendaciones realistas (sin sugerir incumplir obligaciones esenciales) y un **Plan Sugerido editable** para el siguiente mes o quincena.

---

## Arquitectura y Cumplimiento de Requerimientos

| Capa | Implementación | Requerimientos Cubiertos |
| :--- | :--- | :--- |
| **Seguridad y Aislamiento** | PostgreSQL **Row Level Security (`FORCE ROW LEVEL SECURITY`)** con rol sin privilegios de dueño (`cuentaclara_app` con `NOBYPASSRLS`) y propagación por conexión en `RlsAwareDataSource`. Contraseñas con `BCrypt(12)` y `JWT` de vida corta. | `RF-01`, `RF-14`, `RNF-01`, `RNF-02`, `RNF-04` (Ley 1581 de 2012) |
| **Ingesta, ClamAV y XML DIAN** | Validación por *magic bytes*, límite de 10 MB, escaneo **ClamAV**, detección de duplicados por `SHA-256`, `CUFE` o `NIT + Factura`, worker asíncrono con Redis y parser **XML UBL 2.1 DIAN**. | `RF-02`, `RF-03`, `RF-04`, `RF-06`, `RNF-03`, `RNF-05` |
| **OCR, Confirmación e Historial** | **Apache PDFBox** para PDF digital y **Tesseract OCR (`Tess4J`)** para fotos y PDF escaneados. Nada se guarda sin confirmación del usuario (`AWAITING_CONFIRMATION` -> `INDEXED`). Gastos manuales con adjunto opcional. | `RF-02`, `RF-04`, `RF-05`, `RF-10` |
| **Indexación Vectorial y Categorización** | Fragmentación por factura, grupo de ítems y ventanas con solapamiento en extractos. Embeddings versionados (`1536` dims) en `pgvector`. Eliminación segura de la copia temporal solo tras verificar la indexación. Aprendizaje de correcciones de categoría aislado por usuario. | `RF-07`, `RF-08`, `RF-09`, `RNF-08` |
| **Análisis Mensual y Presupuesto** | Cálculos 100% en SQL y Java determinista. Compara perfil declarado vs. gastos reales por bloque (`NEEDS`, `WANTS`, `SAVINGS`), alerta excesos esenciales con viabilidad realista y permite aceptar/editar/ignorar el plan sugerido. | `RF-11`, `RF-13`, `RF-15`, `RF-16`, `RF-17`, `RNF-06` |
| **Chat Híbrido con Citas** | Herramientas deterministas `consultar_transacciones` (SQL parametrizado) y `buscar_documentos` (`pgvector` + `tsvector` con **Reciprocal Rank Fusion**). Toda respuesta cita su fuente (`XML:...|CUFE:...`, `PDF:...|PÁG:...`, `IMG:...`, `MANUAL:...`). | `RF-12`, `RNF-06`, `RNF-09` |

---

## Ejecución Rápida con Docker Compose

1. Copiar variables de entorno de ejemplo:
   ```bash
   cp .env.example .env
   ```
2. Levantar PostgreSQL 16 + `pgvector` y Redis 7:
   ```bash
   docker compose up -d
   ```
3. Ejecutar la aplicación Spring Boot:
   ```bash
   mvn spring-boot:run
   ```
4. Abrir en el navegador:
   * Registro e inicio de sesión: `http://localhost:8080/auth/login`
   * Cuestionario financiero y regla 50/30/20: `http://localhost:8080/profile`
   * Panel principal (Carga, Confirmación, Gastos Manuales, Chat RAG e Informes): `http://localhost:8080/dashboard`

---

## Ejecución de la Suite de Pruebas Automatizadas (Testcontainers)

Todas las pruebas levantan automáticamente un contenedor real `pgvector/pgvector:pg16` mediante **Testcontainers**:

```bash
mvn test
```

Incluye:
* `RlsTenantIsolationIntegrationTest`: Prueba automatizada de no-fuga de datos entre dos usuarios bajo PostgreSQL RLS (`RNF-01`).
* `TwentyInvoicesExtractionAccuracyTest`: Evaluación de exactitud `>= 85 %` sobre las 20 facturas de prueba (`RF-04`).
* `ThirtyQuestionsRagEvaluationTest`: Evaluación de calidad del RAG `>= 80 %` con citas verificables sobre las 30 preguntas de prueba (`RNF-09`).
* `MonthlyReportIntegrationTest`: Verificación del cierre mensual SQL, recomendaciones con evidencia y edición del plan sugerido (`RF-13`, `RF-16`).
