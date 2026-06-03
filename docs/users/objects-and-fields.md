# Objects and Fields

This guide explains how to define your data model in OSC: creating objects, adding fields, and setting validation constraints.

## Standard Objects

OSC comes pre-configured with three standard objects. You can use them as-is or extend them with custom fields.

| Object | API Name | Key Fields |
|---|---|---|
| Account | `Account` | `name`, `industry`, `annual_revenue`, `website` |
| Contact | `Contact` | `first_name`, `last_name`, `email`, `phone`, `account_id` |
| Project | `Project` | `name`, `status`, `start_date`, `end_date`, `owner_id` |

## Creating a Custom Object

```http
POST /api/v1/metadata/objects
Authorization: Bearer <token>
Content-Type: application/json

{
  "apiName": "Invoice",
  "label": "Invoice",
  "labelPlural": "Invoices",
  "auditable": true
}
```

**Response:**
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "apiName": "Invoice",
  "label": "Invoice",
  "labelPlural": "Invoices",
  "auditable": true,
  "tenantId": "...",
  "createdAt": "2026-05-27T10:00:00Z"
}
```

### Object properties

| Property | Required | Description |
|---|---|---|
| `apiName` | Yes | Unique name. Letters, numbers, underscores. Suffix custom objects with `__c` by convention (e.g., `Invoice__c`) |
| `label` | Yes | Display name (singular) |
| `labelPlural` | No | Display name (plural). Defaults to `label + "s"` |
| `auditable` | No | If `true`, changes are logged to the audit trail. Default: `false` |

## Listing Objects

```http
GET /api/v1/metadata/objects
Authorization: Bearer <token>
```

Returns all objects defined for your tenant.

## Adding Fields to an Object

```http
POST /api/v1/metadata/objects/Invoice__c/fields
Authorization: Bearer <token>
Content-Type: application/json

{
  "apiName": "amount",
  "label": "Amount",
  "fieldType": "NUMBER",
  "required": true,
  "constraints": {
    "min": 0
  }
}
```

### Field properties

| Property | Required | Description |
|---|---|---|
| `apiName` | Yes | Unique within the object. Letters, numbers, underscores |
| `label` | Yes | Display name |
| `fieldType` | Yes | `TEXT`, `NUMBER`, `BOOLEAN`, `DATE`, `DATETIME`, `PICKLIST`, `LOOKUP` |
| `required` | No | Whether records must provide this field. Default: `false` |
| `constraints` | No | Type-specific constraints (see below) |

### Field type constraints

#### TEXT
```json
{
  "minLength": 1,
  "maxLength": 255,
  "pattern": "^[A-Z]{2}-[0-9]+$"
}
```

#### NUMBER
```json
{
  "min": 0,
  "max": 1000000,
  "precision": 2
}
```

#### PICKLIST
```json
{
  "values": ["Draft", "Submitted", "Approved", "Rejected"]
}
```

#### LOOKUP
```json
{
  "referenceTo": "Account"
}
```
Stores the UUID of the referenced record.

#### DATE / DATETIME
No constraints — any valid ISO 8601 date is accepted.

#### BOOLEAN
No constraints.

## Listing Fields

```http
GET /api/v1/metadata/objects/Invoice__c/fields
Authorization: Bearer <token>
```

Returns all fields for the specified object.

## Updating a Field

```http
PATCH /api/v1/metadata/objects/Invoice__c/fields/amount
Authorization: Bearer <token>
Content-Type: application/json

{
  "label": "Invoice Amount",
  "constraints": {
    "min": 0.01,
    "max": 999999.99
  }
}
```

Only `label`, `required`, and `constraints` can be updated after creation. The `apiName` and `fieldType` are immutable.

## Deleting an Object or Field

```http
DELETE /api/v1/metadata/objects/Invoice__c
DELETE /api/v1/metadata/objects/Invoice__c/fields/notes
Authorization: Bearer <token>
```

**Warning:** Deleting an object deletes all records of that type and all associated metadata (fields, layouts, validation rules, automations). This action is irreversible.

Deleting a field removes it from all existing records' stored data.

## Example: Complete Invoice Object

```bash
# 1. Create the object
curl -X POST /api/v1/metadata/objects \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"apiName":"Invoice__c","label":"Invoice","auditable":true}'

# 2. Add fields
curl -X POST /api/v1/metadata/objects/Invoice__c/fields \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"apiName":"invoice_number","label":"Invoice #","fieldType":"TEXT","required":true}'

curl -X POST /api/v1/metadata/objects/Invoice__c/fields \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"apiName":"amount","label":"Amount","fieldType":"NUMBER","required":true,"constraints":{"min":0.01}}'

curl -X POST /api/v1/metadata/objects/Invoice__c/fields \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"apiName":"status","label":"Status","fieldType":"PICKLIST","required":true,"constraints":{"values":["Draft","Sent","Paid","Overdue"]}}'

curl -X POST /api/v1/metadata/objects/Invoice__c/fields \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"apiName":"due_date","label":"Due Date","fieldType":"DATE","required":true}'

curl -X POST /api/v1/metadata/objects/Invoice__c/fields \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"apiName":"account_id","label":"Account","fieldType":"LOOKUP","constraints":{"referenceTo":"Account"}}'
```

## AI-Assisted Object Creation (Optional)

If your administrator has enabled AI features, you can describe an object in plain language:

```http
POST /api/v1/ai/suggest-metadata
Authorization: Bearer <token>
Content-Type: application/json

{
  "prompt": "I need an object to track support tickets with priority, status, category, and a description."
}
```

The AI returns a **proposal** — you must review and confirm it before anything is created. No data is modified automatically.
