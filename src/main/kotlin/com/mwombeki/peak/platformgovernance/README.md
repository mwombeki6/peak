# 🛡 Platform Governance Module

## What is it?
This is the **Security and Manager's Office** for the entire platform. While `TenantManagement` handles the guests, `PlatformGovernance` is where the "Big Boss" (Platform Admin) makes final decisions about which hotels are allowed to stay open.

## Key Responsibilities
- **Oversight:** Reviewing and approving new tenants before they can go live.
- **Suspension:** Freezing accounts if they violate rules.
- **Auditing:** Keeping a strict log of every management action taken by operators.

## The "Plugs" (API & Ports)

###  Web API (External)
- `POST /api/v1/governance/tenants/{id}/approve`: Activates a pending tenant.
- `POST /api/v1/governance/tenants/{id}/suspend`: Temporarily disables a tenant.

### 🔌 Internal Ports
- `TenantGovernancePort`: The interface defining how the platform is managed.

## Security 
This module is strictly guarded. Only users with the following badges (roles) from Keycloak can enter:
- `ROLE_PLATFORM_SUPER_ADMIN`
- `ROLE_PLATFORM_OPERATOR`

## Database Tables 
- `tenant_lifecycle_logs`: The "Black Box" that records when and why a tenant was approved or suspended.
