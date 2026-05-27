# OSC — User Documentation

Welcome to OSC (Open Service Cloud), a platform for building and running business applications without writing code.

## What is OSC?

OSC lets your organization define, customize, and use business objects — like Accounts, Contacts, Projects, or any custom object your team needs — entirely through configuration. No deployments, no developer involvement for data model changes.

Each organization (called a **tenant**) has its own isolated space. Your data is completely separate from other organizations on the platform.

## Documentation

| Document | Who it's for |
|---|---|
| [concepts.md](concepts.md) | Everyone — understand the OSC model |
| [objects-and-fields.md](objects-and-fields.md) | Admins — define your data model |
| [records.md](records.md) | Users & Admins — work with data |
| [views-and-layouts.md](views-and-layouts.md) | Admins — customize how data is displayed |
| [automation-guide.md](automation-guide.md) | Admins — automate business processes |

## Quick Start

1. **Define an object** — [objects-and-fields.md](objects-and-fields.md)  
   Create an object type like `Invoice` with fields: `amount`, `due_date`, `status`

2. **Create records** — [records.md](records.md)  
   Add, update, search, and delete records for your object

3. **Customize the view** — [views-and-layouts.md](views-and-layouts.md)  
   Configure which columns appear in lists and how forms are laid out

4. **Add automation** — [automation-guide.md](automation-guide.md)  
   Validate data and trigger actions automatically

## Accessing OSC

OSC provides a **REST API** and a **React web interface**. Admins typically use the API to configure objects and fields; users interact with the web interface to manage records.

All API calls require an `Authorization: Bearer <token>` header with a valid JWT token. Contact your administrator to obtain credentials.

**Base URL:** `https://your-osc-instance.example.com/api/v1`
