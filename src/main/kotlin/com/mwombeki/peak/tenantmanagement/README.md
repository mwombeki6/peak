#  Tenant Management Module

## What is it?
This is the **Reception Desk** of our application. Its primary job is to handle the onboarding and lifecycle of "Tenants" (the companies or hotels that use our software).

## Key Responsibilities
- **Registration:** Welcoming new tenants and creating their initial records.
- **Profile Management:** Storing business details like registration numbers, contact info, and currency preferences.
- **Status Control:** Keeping track of whether a tenant is `ACTIVE`, `PENDING_VERIFICATION`, or `SUSPENDED`.

## The "Plugs" (API & Ports)

###  Web API (External)
- `POST /api/v1/tenants/register`: Used to sign up a new company.
- `GET /api/v1/tenants/{id}`: Retrieves details about a specific company.
- `PATCH /api/v1/tenants/{id}/status`: Updates the status (e.g., activating a tenant).

###  Internal Ports
- `TenantOnboardingPort`: The standard socket used by the web controller to talk to the business logic.

## Data Structure (The Map)
- `api/`: Contains the public "Models" (Guest Record Cards) and "Ports" (Sockets).
- `internal/`: The "Staff Only" area containing the actual business logic (`TenantOnboardingService`) and database clerks (`TenantRepository`).

## Database Tables 🗄
- `tenants`: The main list of companies.
- `tenant_profiles`: Detailed business information for each company.
