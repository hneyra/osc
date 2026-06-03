# OSC — Architecture Reference

## 1. System Context

OSC is a **metadata-driven, multi-tenant PaaS** that lets tenants define business objects at runtime without code deployments. Think Salesforce's object model as a service.

```mermaid
C4Context
  title OSC System Context

  Person(admin, "Tenant Admin", "Configures objects, fields, layouts")
  Person(user, "End User", "Creates and queries records")
  Person(dev, "Developer", "Calls REST API, configures webhooks")

  System(osc, "OSC Platform", "Runtime-configurable business app engine. Multi-tenant.")

  System_Ext(pg, "PostgreSQL 16", "Primary datastore with RLS")
  System_Ext(ai, "Claude / LLM", "Spring AI – NL proposals only")
  System_Ext(webhook, "External Systems", "Webhook consumers (HTTPS)")
  System_Ext(aws, "AWS ECS / RDS", "Compute and managed DB")

  Rel(admin, osc, "Manages metadata via REST API")
  Rel(user, osc, "CRUD records via REST API or React frontend")
  Rel(dev, osc, "Integrates via REST API / webhooks")
  Rel(osc, pg, "R2DBC — reactive, parameterized queries")
  Rel(osc, ai, "Proposes metadata / query translations")
  Rel(osc, webhook, "Delivers signed event payloads (HMAC-SHA256)")
  Rel(osc, aws, "Deployed on ECS Fargate via Pulumi")
```

## 2. Two-Plane Architecture

OSC separates concerns into two planes with radically different characteristics.

```mermaid
flowchart TB
  subgraph MP["Metadata Plane (stable, cached)"]
    direction LR
    MD_OBJ[md_object]
    MD_FIELD[md_field]
    MD_LAYOUT[md_layout]
    MD_VIEW[md_list_view]
    MD_RULE[md_validation_rule]
    MD_AUTO[md_automation]
  end

  subgraph DP["Data Plane (unbounded growth)"]
    RECORD[record\ntenant_id, object_name, data JSONB, promoted columns]
  end

  subgraph CACHE["Caffeine L1 Cache (per tenant)"]
    CACHE_OBJ[ObjectDefinition]
    CACHE_FIELD[FieldDefinition[]]
  end

  MP -->|loaded on first access| CACHE
  CACHE -->|drives| DP
  DP -->|field access stats feed| CACHE
```

**Metadata Plane properties:**
- Changes infrequently (admin actions only)
- Fully cached in Caffeine per tenant
- Drives everything: rendering, validation, queries, security

**Data Plane properties:**
- Grows unboundedly as users create records
- Single universal `record` table with JSONB `data` column
- GIN index on `data` for fast attribute lookups
- Hot fields can be promoted to native columns without schema changes (see ADR-002)

## 3. Layered Architecture

```mermaid
flowchart TD
  Client([HTTP Client / React Frontend])

  subgraph API["api module — Spring WebFlux"]
    WF[WebFilter chain\nCORS → CorrelationId → RateLimit → TenantContext → UserContext → MDC → Metrics]
    CTRL[DynamicRecordController\n/api/v1/{objectName}/records]
  end

  subgraph SEC["security module"]
    PERM[PermissionChecker\nObject-level CRUD]
    FLS[FlsFilter\nField-level strip]
    REC_ACC[RecordAccessEvaluator\nOwnership / sharing rules]
  end

  subgraph VAL["automation module"]
    VAL_ENG[ValidationEngine\ndeclarative DSL]
    AUTO_ENG[AutomationEngine\nflows + user-code sandbox]
    OUTBOX[OutboxWorker\nasync event delivery]
  end

  subgraph QE["query-engine module"]
    PARSER[QueryParser\nSOQL-like → AST]
    TRANS[QueryTranslator\nAST → parameterized SQL]
    EXEC[R2dbcQueryExecutor]
  end

  subgraph PERSIST["persistence module"]
    SVC[DynamicPersistenceService]
    REPO[R2dbcRecordRepository]
    FLY[Flyway migrations]
  end

  subgraph META["metadata-engine module"]
    ENGINE[CaffeineMetadataEngine]
    COERCE[FieldCoercionEngine]
  end

  DB[(PostgreSQL 16\nRLS active)]

  Client --> WF --> CTRL
  CTRL --> PERM --> FLS --> REC_ACC
  CTRL --> VAL_ENG --> AUTO_ENG
  AUTO_ENG --> OUTBOX
  CTRL --> PARSER --> TRANS --> EXEC
  CTRL --> SVC --> REPO --> DB
  EXEC --> DB
  SVC --> META --> ENGINE
  REPO --> FLY
```

## 4. Module Dependency Graph

```mermaid
graph LR
  API[api] --> SEC[security]
  API --> AUTO[automation]
  API --> QE[query-engine]
  API --> AI[ai]
  API --> INT[integrations]

  AUTO --> PERSIST[persistence]
  AUTO --> QE
  AUTO --> SEC

  QE --> META[metadata-engine]
  SEC --> META
  PERSIST --> META

  INT --> PERSIST
  INT --> AUTO

  META:::leaf
  PERSIST:::mid
  QE:::mid
  SEC:::mid

  classDef leaf fill:#e8f5e9,stroke:#388e3c
  classDef mid fill:#e3f2fd,stroke:#1976d2
```

`metadata-engine` and `persistence` have no dependencies on other OSC modules — they are the foundation.

## 5. Reactive Request Flow

All I/O is non-blocking. The event loop thread is never blocked.

```mermaid
sequenceDiagram
  participant C as HTTP Client
  participant WF as WebFilter chain
  participant CTRL as Controller
  participant SEC as SecurityModule
  participant VAL as ValidationEngine
  participant SVC as PersistenceService
  participant DB as PostgreSQL (R2DBC)

  C->>+WF: POST /api/v1/Order__c/records
  WF->>WF: Extract JWT → tenant_id, user_id
  WF->>WF: Set Reactor Context (tenant, user, correlationId)
  WF->>+CTRL: Mono<ServerRequest>

  CTRL->>+SEC: checkPermission(CREATE, Order__c)
  SEC-->>-CTRL: Mono<Void> (or 403)

  CTRL->>+VAL: validate(record, rules)
  VAL-->>-CTRL: Mono<List<Violation>> (or error)

  CTRL->>+SVC: create(tenant_id, objectName, data)
  SVC->>+DB: INSERT … (parameterized)
  DB-->>-SVC: RecordEntity
  SVC->>DB: INSERT outbox_event (same logical tx)
  SVC-->>-CTRL: Mono<RecordEntity>

  CTRL->>SEC: applyFls(record, user)
  CTRL-->>C: 201 Created (filtered fields)
```

## 6. Multi-Tenancy Model

Defense-in-depth: two independent enforcement layers.

```mermaid
flowchart LR
  JWT([JWT Token]) -->|claim: tenant_id| FILTER[TenantContextFilter]
  FILTER -->|Reactor Context| SVC[Service Layer]

  SVC -->|binds :tenantId param| R2DBC[R2DBC Query]
  R2DBC -->|WHERE tenant_id = :tenantId| PG[(PostgreSQL)]

  PG -->|RLS policy check| RLS{SET app.current_tenant\n= :tenantId ?}
  RLS -->|Yes| ROW[Row returned]
  RLS -->|No| BLOCKED[Blocked by DB]

  style BLOCKED fill:#ffcdd2,stroke:#c62828
  style ROW fill:#c8e6c9,stroke:#2e7d32
```

**Rules enforced at all times:**
- `tenant_id` is extracted from the JWT only — never from request body or headers
- Every query has an explicit `tenant_id = :tenantId` bind parameter
- RLS policy on every table enforces the same constraint at DB level
- Cross-tenant access is impossible by construction

## 7. Data Model (simplified ERD)

```mermaid
erDiagram
  tenant {
    uuid id PK
    text name
    text slug
    bool active
  }

  md_object {
    uuid id PK
    uuid tenant_id FK
    text api_name
    text label
    bool auditable
  }

  md_field {
    uuid id PK
    uuid tenant_id FK
    uuid object_id FK
    text api_name
    text label
    text field_type
    text storage_kind
    bool required
    jsonb constraints
  }

  md_validation_rule {
    uuid id PK
    uuid tenant_id FK
    uuid object_id FK
    text formula
    text error_message
    bool active
  }

  md_layout {
    uuid id PK
    uuid tenant_id FK
    uuid object_id FK
    text name
    jsonb sections
  }

  md_automation {
    uuid id PK
    uuid tenant_id FK
    uuid object_id FK
    text trigger_type
    jsonb conditions
    jsonb actions
  }

  record {
    uuid id PK
    uuid tenant_id FK
    text object_name
    jsonb data
    uuid owner_id
    timestamptz created_at
    timestamptz updated_at
  }

  outbox_event {
    uuid id PK
    uuid tenant_id FK
    text event_type
    jsonb payload
    text status
    int retry_count
  }

  tenant ||--o{ md_object : owns
  md_object ||--o{ md_field : has
  md_object ||--o{ md_validation_rule : governs
  md_object ||--o{ md_layout : presents
  md_object ||--o{ md_automation : triggers
  tenant ||--o{ record : stores
```

## 8. Event-Driven Outbox Pattern

```mermaid
sequenceDiagram
  participant SVC as PersistenceService
  participant DB as record + outbox_event tables
  participant WORKER as OutboxWorker (async)
  participant WEBHOOK as External Webhook Consumer

  SVC->>DB: BEGIN TX\nINSERT record\nINSERT outbox_event (status=PENDING)
  DB-->>SVC: COMMIT

  loop Poll every N seconds
    WORKER->>DB: SELECT … WHERE status=PENDING LIMIT 50
    DB-->>WORKER: events[]
    WORKER->>WEBHOOK: POST payload (HMAC-SHA256 signed)
    alt Success
      WORKER->>DB: UPDATE status=DELIVERED
    else Failure (retry < max)
      WORKER->>DB: UPDATE status=RETRY, retry_count++
    else Max retries
      WORKER->>DB: UPDATE status=DEAD_LETTER
    end
  end
```

## 9. AI Layer (off critical path)

```mermaid
flowchart LR
  USER([Admin User]) -->|Natural language| AI_SVC[NlToMetadataService\nNlToQueryService]
  AI_SVC -->|Prompt| LLM([Claude / LLM via Spring AI])
  LLM -->|JSON suggestion| AI_SVC
  AI_SVC -->|Validate against JSON Schema| VALID{Valid?}
  VALID -->|Yes| CONFIRM[Return proposal\nUser confirms]
  VALID -->|No| REJECT[Return error]
  CONFIRM -->|User approves| META[MetadataEngine.create\nor QueryEngine.execute]

  style LLM fill:#ffe0b2,stroke:#e65100
  style CONFIRM fill:#e8f5e9,stroke:#388e3c
  style REJECT fill:#ffcdd2,stroke:#c62828
```

**AI is never on the critical data path.** It only proposes; a human confirmation is always required before metadata is modified or a query is executed.

## 10. Infrastructure Overview

```mermaid
flowchart TB
  subgraph AWS["AWS (via Pulumi TypeScript)"]
    subgraph VPC["Shared VPC (hneyra/iaac stack)"]
      subgraph ECS["ECS Fargate"]
        APP[OSC API Container\nJava 25 / Spring WebFlux]
        FRONT[Frontend Container\nReact + Nginx]
      end
      ALB[Application Load Balancer]
      RDS[(RDS PostgreSQL 16\nMulti-AZ)]
      ECR[Container Registry\nECR]
    end
  end

  INTERNET([Internet]) --> ALB
  ALB --> APP
  ALB --> FRONT
  APP --> RDS
  ECR --> ECS

  subgraph PULUMI["Pulumi IaC (infrastructure/)"]
    DEV[dev/index.ts]
    PROD[prod/index.ts]
    NET[src/networking.ts]
    DB_STACK[src/database.ts]
    COMPUTE[src/compute.ts]
  end

  PULUMI -->|provisions| AWS
```
