# Motor de Aplicaciones de Negocio Configurable en Runtime
## Documento Maestro de Arquitectura y Plan de Ejecución

> **Estado:** Borrador v1.0 — documento vivo
> **Propósito:** Servir como guía única de arquitectura y plan de implementación, optimizado para ejecutarse paso a paso con Claude Code (backend/lógica) y Claude (diseño frontend).
> **Cómo usar este documento:** Es la fuente de verdad del proyecto. Se versiona en git junto al código. El archivo `CLAUDE.md` (incluido al final) lo referencia para que Claude Code lo lea al inicio de cada sesión.

---

## 0. Resumen ejecutivo

Estamos construyendo una **PaaS/SaaS multi-tenant** que funciona como un *motor de aplicaciones de negocio configurable en runtime*. No es un CRM: es la plataforma sobre la que se podrían construir CRMs, ERPs, gestores de proyectos o cualquier aplicación de datos de negocio, sin redesplegar código.

La idea central, heredada de cómo funciona Salesforce internamente, es que **casi todo es metadata interpretada en runtime**, no clases compiladas. Cuando un usuario crea un objeto, un campo o una regla, se escriben registros de metadata que el motor lee dinámicamente; no hay release de por medio.

### Decisiones ya tomadas

| Decisión | Elección | Razón |
|---|---|---|
| Modelo | Multi-tenant (PaaS/SaaS) | Requisito de negocio |
| Base de datos | PostgreSQL (híbrido columnas + JSONB) | ACID, relaciones, reportes; JSONB da flexibilidad de esquema |
| Backend | Spring Boot 4.x + Spring WebFlux | Reactivo end-to-end; Java 25 |
| DB Access | R2DBC (reactivo) | No bloqueo; coherente con WebFlux |
| IA | Spring AI 2 M6 | Lenguaje natural → metadata, consultas asistidas |
| Frontend | React (motor de renderizado por metadata) | Experiencia previa; UI generada dinámicamente |
| Build | Gradle con Kotlin DSL | Flexibilidad y tipado en el build |
| Infra | Pulumi TypeScript | IaC tipado; reutiliza servicios de hneyra/iaac |
| Customización (orden) | Objetos/campos → relaciones → vistas/layouts → permisos → validaciones → flujos | Por valor y complejidad creciente |
| Código de usuario | Sí, vía sandbox seguro (ver §7) | Requisito; máxima extensibilidad |
| Escala | Decenas de tenants, miles de registros (crecer) | No optimizar prematuramente |
| Integraciones | APIs REST entrada/salida + webhooks | Requisito |
| Equipo | Solo al inicio, crece después | Plan modular para incorporar gente |

---

## 1. Glosario (vocabulario del dominio)

- **Tenant (Org):** organización aislada. Toda la data y metadata pertenece a un tenant.
- **Object (Entidad):** definición de un tipo de registro (ej. `Account`, `Project`). Puede ser *estándar* o *custom*.
- **Field (Campo):** atributo de un objeto. Tiene tipo, validaciones, y puede ser de sistema, core o custom.
- **Record (Registro):** instancia concreta de un objeto (una fila de datos).
- **Layout:** definición de cómo se muestra/edita un objeto (qué campos, en qué orden, agrupados cómo).
- **View (List View):** definición de una vista de lista (columnas, filtros, orden).
- **Validation Rule:** regla declarativa que un registro debe cumplir.
- **Automation / Flow:** lógica disparada por eventos (crear/editar/borrar registro).
- **Permission Set / Profile:** conjunto de permisos a nivel objeto, campo y registro.
- **Apex-like / User Code:** lógica de código escrita por desarrolladores del tenant, ejecutada en sandbox.
- **Metadata Engine:** subsistema que carga, cachea e interpreta la metadata.
- **Query Engine:** subsistema que traduce consultas lógicas (tipo SOQL) a SQL seguro.

---

## 2. Arquitectura general

### 2.1 Los dos planos

1. **Plano de metadata (la definición):** qué objetos/campos/reglas/vistas/permisos existen. Estructurado, relativamente estable, cacheable.
2. **Plano de datos (los registros):** instancias reales creadas por usuarios. Crece sin límite, su forma depende de la metadata.

Toda operación de datos se valida e interpreta contra el plano de metadata.

### 2.2 Vista de capas (backend)

```
┌─────────────────────────────────────────────────────────────┐
│  API dinámica (REST autogenerada + GraphQL opcional)          │
│  Se construye a partir de la metadata de cada tenant          │
├─────────────────────────────────────────────────────────────┤
│  Security / Permissions Layer                                  │
│  Filtra por tenant, objeto, campo y registro (FLS + RLS)      │
├─────────────────────────────────────────────────────────────┤
│  Validation & Automation Engine                                │
│  Reglas de validación, flujos, triggers, user-code (sandbox)  │
├─────────────────────────────────────────────────────────────┤
│  Query Engine (tipo SOQL → SQL parametrizado vía R2DBC)        │
├─────────────────────────────────────────────────────────────┤
│  Dynamic Persistence Layer                                     │
│  Traduce operaciones lógicas a SQL real (columnas + JSONB)    │
│  Todo via R2DBC (reactivo, sin bloqueo)                       │
├─────────────────────────────────────────────────────────────┤
│  Metadata Engine (carga + caché Caffeine + invalidación)      │
├─────────────────────────────────────────────────────────────┤
│  PostgreSQL 16+  (metadata tables + data tables, RLS tenant)  │
└─────────────────────────────────────────────────────────────┘
                          ▲
                          │
              ┌───────────┴────────────┐
              │  AI Layer (Spring AI)   │  (transversal, fuera del path crítico)
              │  NL → metadata, NL→query│
              └─────────────────────────┘
```

**Principio rector:** la IA nunca está en el camino crítico de una operación de datos. El motor es determinista; la IA es una capa de productividad encima.

### 2.3 Frontend como motor de renderizado

El frontend **no** tiene componentes hardcodeados por entidad. Recibe metadata y renderiza:

- `LayoutRenderer` — compone la página de detalle/edición a partir de un layout.
- `FieldRenderer` — un renderer por tipo de campo (text, number, date, picklist, lookup, etc.).
- `ListViewRenderer` — tablas de lista con filtros/orden definidos por metadata.
- `AppShell` — navegación, selector de objeto, búsqueda global.

Se diseñan ~30 componentes base de altísima calidad **una vez** (con Claude Design), y el motor los compone según metadata.

---

## 3. Multi-tenancy

### 3.1 Estrategia elegida: tenant compartido con aislamiento por fila (shared-schema + RLS)

- Cada tabla (metadata y datos) tiene `tenant_id NOT NULL`.
- RLS activo en PostgreSQL: `CREATE POLICY tenant_isolation ON <tabla> USING (tenant_id = current_setting('app.current_tenant')::uuid)`.
- La aplicación setea `app.current_tenant` por conexión/transacción (vía R2DBC + `SET LOCAL`), derivado del JWT autenticado.
- **Defensa en profundidad:** además de RLS, la capa de aplicación también filtra por `tenant_id`.

### 3.2 Resolución de tenant

El `tenant_id` se deriva **siempre** del contexto de seguridad (JWT claim), nunca de un parámetro que el cliente pueda manipular. Se transporta en el Reactor `Context` durante todo el request pipeline (no ThreadLocal — incompatible con reactive).

---

## 4. Modelo de datos

### 4.1 Tablas de metadata (núcleo del sistema)

```sql
-- Organización / tenant
CREATE TABLE tenant (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL,
    slug          TEXT UNIQUE NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Definición de un objeto (entidad)
CREATE TABLE md_object (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    api_name      TEXT NOT NULL,
    label         TEXT NOT NULL,
    label_plural  TEXT NOT NULL,
    is_custom     BOOLEAN NOT NULL DEFAULT true,
    storage_table TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, api_name)
);

-- Definición de un campo
CREATE TABLE md_field (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    api_name      TEXT NOT NULL,
    label         TEXT NOT NULL,
    field_type    TEXT NOT NULL,
    is_required   BOOLEAN NOT NULL DEFAULT false,
    is_unique     BOOLEAN NOT NULL DEFAULT false,
    is_custom     BOOLEAN NOT NULL DEFAULT true,
    storage_kind  TEXT NOT NULL DEFAULT 'JSONB',
    storage_key   TEXT,
    config        JSONB,
    reference_to  UUID REFERENCES md_object(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, object_id, api_name)
);

-- Reglas de validación (declarativas)
CREATE TABLE md_validation_rule (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    name          TEXT NOT NULL,
    expression    TEXT NOT NULL,
    error_message TEXT NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true
);

-- Layouts (cómo se ve/edita un objeto)
CREATE TABLE md_layout (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    name          TEXT NOT NULL,
    definition    JSONB NOT NULL
);

-- Vistas de lista
CREATE TABLE md_list_view (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    name          TEXT NOT NULL,
    definition    JSONB NOT NULL
);

-- Automatizaciones / flujos
CREATE TABLE md_automation (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    trigger_event TEXT NOT NULL,
    kind          TEXT NOT NULL,
    definition    JSONB,
    is_active     BOOLEAN NOT NULL DEFAULT true
);
```

### 4.2 Tabla de datos

```sql
-- Tabla universal de registros
CREATE TABLE record (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    object_id     UUID NOT NULL REFERENCES md_object(id),
    name          TEXT,
    owner_id      UUID,
    data          JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID
);

CREATE INDEX idx_record_tenant_object ON record (tenant_id, object_id);
CREATE INDEX idx_record_data_gin      ON record USING GIN (data jsonb_path_ops);
```

### 4.3 Estrategia JSONB universal

Empezar con **tabla universal + JSONB**. No hay DDL dinámico en runtime. El campo `storage_kind`/`storage_key` en `md_field` permite promover campos calientes a columnas reales vía Flyway migration en el futuro, sin romper la API.

---

## 5. Query Engine (tipo SOQL)

- Entrada: consulta lógica sobre objetos/campos por `api_name`.
- Valida cada objeto/campo contra metadata (rechaza lo que no existe o no se tiene permiso).
- Traduce: campos COLUMN → columna; campos JSONB → `data->>'key'` con cast por tipo.
- Aplica filtros de seguridad (tenant, FLS, RLS) **antes** de ejecutar.
- Todo via R2DBC con bindings parametrizados — jamás concatenación de strings.

---

## 6. Validación y automatización declarativa

### 6.1 DSL de expresiones seguro

Las reglas de validación y los flujos declarativos usan un **DSL de expresiones acotado**: comparaciones, lógica booleana, funciones puras de fecha/texto/número, referencias a campos. Evaluado con intérprete propio con whitelist. **Nunca** como código Java/JVM directo.

### 6.2 Ciclo de ejecución

```
1. Resolver tenant + permisos
2. Cargar metadata del objeto (desde caché)
3. Coerción/validación de tipos de los campos entrantes
4. Ejecutar automatizaciones BEFORE (declarativas y user-code)
5. Ejecutar validation rules
6. Persistir (transacción R2DBC)
7. Publicar eventos AFTER al outbox (misma transacción)
8. [Async] Worker del outbox entrega webhooks y side effects
```

---

## 7. Código de usuario (user-code)

**Recomendación para v1:** empezar por DSL/scripting controlado (opción A), diseñar el `UserCodeExecutor` como un puerto para enchufar WASM o microVM en el futuro. Reglas transversales:

- Límites duros de CPU, memoria, tiempo de ejecución y profundidad de recursión.
- Sin acceso de red, disco ni reflexión desde el código de usuario; solo un API controlada (`ExecutionContext`).
- Ejecución siempre con los permisos del usuario, nunca con permisos elevados.
- Auditoría completa de cada ejecución.

---

## 8. Capa de IA (Spring AI 2 M6)

Usos previstos, todos **fuera del path crítico**:

1. **NL → metadata:** genera la metadata como propuesta que el usuario confirma.
2. **NL → query:** preguntas en lenguaje natural → Query Engine DSL (con permisos del usuario).
3. **Asistencia contextual:** ayuda al construir reglas/flujos, sugerencias de campos.

**Principios:** la IA propone, el motor dispone. Toda salida de la IA que modifique el sistema pasa por validación de esquema y confirmación humana.

---

## 9. Integraciones externas

- **API entrante:** API dinámica REST sirve como API pública del tenant.
- **Webhooks salientes:** suscripciones por evento. Publicadas a outbox tras commit; worker entrega con reintentos, backoff y firma HMAC.
- **Llamadas salientes desde flujos/user-code:** solo vía cliente de plataforma con allowlist de dominios, rate-limit y auditoría.

---

## 10. Stack técnico concreto

**Backend**
- Java 25, Spring Boot 4.x, Spring WebFlux (reactivo).
- R2DBC + PostgreSQL 16+ (RLS, JSONB, GIN). Migraciones con Flyway.
- Caché de metadata: Caffeine (AsyncLoadingCache) con invalidación por evento.
- Cola para webhooks/eventos: outbox pattern (tabla + worker reactivo); migrar a Redis/Kafka si hace falta.
- Auth: JWT + OAuth2 (Spring Security). Tenant claim en el token.
- Build: Gradle con Kotlin DSL.

**Frontend**
- React + TypeScript, Vite.
- Estado servidor: TanStack Query. Formularios: React Hook Form + Zod (validación derivada de metadata).
- Design system propio (construido con Claude Design).

**Infra**
- Pulumi TypeScript en `infrastructure/`. Reutiliza servicios de `hneyra/iaac`.
- Docker; docker-compose para desarrollo local.
- Monolito modular al inicio (no microservicios).

---

## 11. Estructura de repositorio

```
/                         (monorepo)
├── CLAUDE.md             # instrucciones para Claude Code
├── ARCHITECTURE.md       # resumen de arquitectura
├── docs/
│   ├── PROJECT.md        # este documento (fuente de verdad)
│   ├── ARCHITECTURE.md   # arquitectura técnica detallada
│   ├── adr/              # Architecture Decision Records
│   └── contracts/        # esquemas de metadata, OpenAPI
├── agents/
│   ├── CLAUDE.md         # instrucciones detalladas para agentes IA
│   └── PROGRAMMERS.md    # guía para desarrolladores humanos
├── backend/
│   ├── metadata-engine/
│   ├── persistence/
│   ├── query-engine/
│   ├── automation/
│   ├── security/
│   ├── api/
│   ├── ai/
│   └── integrations/
├── frontend/
│   ├── design-system/
│   ├── renderer/
│   ├── admin/
│   └── runtime/
└── infrastructure/       # Pulumi TypeScript
```

---

## 12. Plan de ejecución por fases

### Fase 0 — Fundaciones del modelo de metadata `[la más importante]`
- Esquema SQL de tablas `md_*` y `record` (Flyway V1).
- Esquema formal (JSON Schema) de la definición de un objeto/campo en `docs/contracts/`.
- 2 objetos seed (`Account`, `Contact`) cargados como metadata.
- ADR-001: multi-tenancy. ADR-002: JSONB universal con promoción. ADR-003: reactive stack. ADR-004: Pulumi infra.
- **Aceptación:** se puede insertar metadata de un objeto y leerla reactivamente; migraciones corren limpias.

### Fase 1 — Dynamic Persistence Layer
- CRUD de registros dirigido por metadata vía R2DBC.
- Coerción y validación de tipos de campo.
- RLS activo + filtro de aplicación.
- Tenant context en Reactor Context.
- **Aceptación:** suite de tests exhaustiva (incluye aislamiento entre tenants).

### Fase 2 — Query Engine + API dinámica
- Parser de consultas lógicas → SQL parametrizado vía R2DBC.
- Tests de inyección SQL.
- API REST autogenerada por metadata con OpenAPI.
- **Aceptación:** consultas seguras; contrato OpenAPI publicado.

### Fase 3 — Frontend renderer + design system
- Design system (~30 componentes), `FieldRenderer` por tipo, layout system.
- Motor de composición que consume metadata + API.
- Formularios con validación derivada de metadata (Zod generado).
- **Aceptación:** crear/editar/listar registros end-to-end desde la UI.

### Fase 4 — Seguridad y permisos
- Profiles / permission sets en metadata.
- FLS + RLS en Query Engine y API.
- **Aceptación:** un usuario sin permiso a un campo nunca lo ve.

### Fase 5 — Automation engine
- DSL de expresiones seguro.
- `UserCodeExecutor` (puerto) + implementación sandbox.
- Outbox + eventos.
- **Aceptación:** regla de validación bloquea guardado inválido; flujo modifica campo; user-code corre con límites.

### Fase 6 — Integraciones + capa de IA
- Webhooks salientes con reintentos/HMAC.
- Spring AI: NL→metadata y NL→query.
- **Aceptación:** evento dispara webhook firmado; NL genera metadata válida con confirmación.

### Fase 7 — Endurecimiento y multi-equipo
- Observabilidad (logs estructurados, métricas, trazas).
- Rate-limiting, auditoría.
- Performance: índices, promoción de campos calientes.
- Documentación de onboarding.

---

## 13. Puntos abiertos

1. Lenguaje de user-code (Fase 5): DSL propio vs. scripting embebido vs. WASM.
2. Cola de eventos: outbox+worker suficiente, o Redis/Kafka desde antes.
3. GraphQL: además de REST.
4. Caché distribuida: cuándo Redis.
5. Versionado de metadata: historial/rollback de cambios de configuración.
