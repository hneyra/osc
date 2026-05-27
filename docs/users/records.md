# Working with Records

Records are the actual data stored in OSC. Every record belongs to exactly one object type.

## Creating a Record

```http
POST /api/v1/Invoice__c/records
Authorization: Bearer <token>
Content-Type: application/json

{
  "invoice_number": "INV-2026-001",
  "amount": 15000.00,
  "status": "Draft",
  "due_date": "2026-06-30",
  "account_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

**Response (201 Created):**
```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "tenantId": "...",
  "objectName": "Invoice__c",
  "data": {
    "invoice_number": "INV-2026-001",
    "amount": 15000.00,
    "status": "Draft",
    "due_date": "2026-06-30",
    "account_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
  },
  "ownerId": "<your-user-id>",
  "createdAt": "2026-05-27T10:00:00Z",
  "updatedAt": "2026-05-27T10:00:00Z"
}
```

- Required fields must be included or the request is rejected with `400 Bad Request`.
- Validation rules run on create — if a rule fails, the response includes the error message.
- Fields you don't have write permission for are silently ignored.

## Getting a Record by ID

```http
GET /api/v1/Invoice__c/records/7c9e6679-7425-40de-944b-e07fc1f90ae7
Authorization: Bearer <token>
```

Returns `404 Not Found` if the record does not exist or belongs to another tenant.

## Listing Records

```http
GET /api/v1/Invoice__c/records
Authorization: Bearer <token>
```

Returns records using the default list view definition. Supports pagination:

```http
GET /api/v1/Invoice__c/records?page=0&size=25
```

## Querying Records

Use the `q` parameter to pass a SOQL-like query:

```http
GET /api/v1/Invoice__c/records?q=SELECT+invoice_number,amount,status+FROM+Invoice__c+WHERE+status='Draft'+ORDER+BY+due_date+ASC
Authorization: Bearer <token>
```

### Query syntax

```sql
SELECT field1, field2
FROM ObjectApiName
WHERE condition
ORDER BY field ASC|DESC
LIMIT n OFFSET m
```

### Condition operators

| Operator | Example |
|---|---|
| `=` | `status = 'Draft'` |
| `!=` | `status != 'Paid'` |
| `>` `>=` `<` `<=` | `amount >= 1000` |
| `LIKE` | `invoice_number LIKE 'INV-2026-%'` |
| `IN` | `status IN ('Draft', 'Sent')` |
| `IS NULL` | `due_date IS NULL` |
| `IS NOT NULL` | `account_id IS NOT NULL` |
| `AND` `OR` | `amount > 0 AND status != 'Paid'` |

### Examples

```sql
-- Overdue invoices over $10,000
SELECT invoice_number, amount, due_date
FROM Invoice__c
WHERE status = 'Sent' AND due_date < '2026-05-27' AND amount > 10000
ORDER BY due_date ASC

-- Recent contacts
SELECT first_name, last_name, email
FROM Contact
ORDER BY created_at DESC
LIMIT 10
```

**Response:**
```json
{
  "records": [ ... ],
  "totalCount": 45,
  "page": 0,
  "pageSize": 25,
  "hasMore": true
}
```

## Updating a Record

### Full update (PUT)

Replaces the entire record. Fields not included are set to null.

```http
PUT /api/v1/Invoice__c/records/7c9e6679-7425-40de-944b-e07fc1f90ae7
Authorization: Bearer <token>
Content-Type: application/json

{
  "invoice_number": "INV-2026-001",
  "amount": 15000.00,
  "status": "Sent",
  "due_date": "2026-06-30",
  "account_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### Partial update (PATCH)

Updates only the fields included in the request body.

```http
PATCH /api/v1/Invoice__c/records/7c9e6679-7425-40de-944b-e07fc1f90ae7
Authorization: Bearer <token>
Content-Type: application/json

{
  "status": "Paid"
}
```

Both operations run validation rules. If a rule fails, the update is rejected.

## Deleting a Record

```http
DELETE /api/v1/Invoice__c/records/7c9e6679-7425-40de-944b-e07fc1f90ae7
Authorization: Bearer <token>
```

Returns `204 No Content` on success. Returns `404` if the record does not exist or you don't have access.

**Note:** Deletion triggers `AFTER_DELETE` automations before returning.

## Error Responses

### Validation failure

```json
{
  "error": "VALIDATION_FAILURE",
  "violations": [
    {
      "ruleApiName": "amount_must_be_positive",
      "message": "Amount must be greater than zero."
    }
  ]
}
```

### Missing required field

```json
{
  "error": "FIELD_REQUIRED",
  "field": "due_date",
  "message": "Field 'due_date' is required."
}
```

### Not found

```json
{
  "error": "NOT_FOUND",
  "message": "Record not found."
}
```

### Permission denied

```json
{
  "error": "FORBIDDEN",
  "message": "You do not have CREATE permission on Invoice__c."
}
```

## AI-Assisted Queries (Optional)

If enabled, you can describe your query in plain language:

```http
POST /api/v1/ai/suggest-query
Authorization: Bearer <token>
Content-Type: application/json

{
  "prompt": "Show me all invoices that are overdue and have an amount over 5000",
  "objectName": "Invoice__c"
}
```

The AI returns a SOQL proposal that you can review and execute.
