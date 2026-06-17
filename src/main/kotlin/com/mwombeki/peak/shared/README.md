#  Shared Kernel: The Hotel's Foundation

##  What is it? (The "Dummy" Version)
If our application is a **Hotel**, then the **Shared Kernel** is the foundation, the pipes, the electricity, and the common rules that the entire building uses. 

Without the foundation, the rooms (Modules like `Audit` or `TenantManagement`) would just fall over. The Shared Kernel doesn't "do" the business (it doesn't book rooms or cook food), but it provides the **tools** that everyone else uses to do their jobs.

---

##  Key Components

### 1.  Request Context: The "VIP Lanyard" (`shared.context`)
Every time a request (a Guest) enters the hotel, they are given a **Lanyard**. 
- **What’s on it?** Their Name (User ID), their Company (Tenant ID), and their permissions.
- **Why?** Instead of the Guest having to tell every staff member who they are 100 times, the staff member just looks at the Lanyard.
- **Automatic:** The `RequestContextInterceptor` automatically puts the lanyard on the guest the moment they walk through the door.

### 2. 🛡 Security: The "Front Gate" (`shared.security`)
This is the security guard team at the main entrance.
- **Keycloak Integration:** We use a professional ID office (Keycloak) to check if the guest's ID card is real.
- **SecurityConfig:** These are the house rules. "Guests without a badge can't enter the Admin Wing."
- **Tenant Isolation:** A very important rule—guests from Company A are **never** allowed to peek into the rooms of Company B.

### 3.  Common Utilities: The "Toolbelt" (`shared.util`, `shared.dto`, `shared.exception`)
Every staff member (Module) carries the same toolbelt so they all work the same way.
- **Exceptions:** If something goes wrong, everyone uses the same "Emergency Signs."
- **Time Utils:** Everyone's watch is synced to the same clock so logs aren't confusing.
- **IDs:** A standard way to generate "Room Keys" (UUIDs).

### 4.  Reliability: The "Assistant" (`shared.idempotency`, `shared.outbox`)
- **Idempotency:** An assistant who remembers if a guest already asked for something. If a guest says "Book me a room" twice by mistake, the assistant says, "I already did that for you," instead of booking two rooms.
- **Outbox:** A "To-Do" list for messages. If the hotel needs to send an email but the internet is down, the clerk puts the message in the Outbox and waits until the internet is back to send it.

---

##  How it's built (The Technical Map)

### `shared.context`
- `RequestContext`: The data structure of the Lanyard.
- `RequestContextHolder`: The "hook" on the wall where the Lanyard is kept for easy access.

###  `shared.security`
- `SecurityConfig`: The master rules for who can go where.
- `TenantContextFilter`: The tool that looks at the guest's ID and finds out which Company (Tenant) they belong to.

###  `shared.exception`
- `PeakException`: The base of all error messages in the project.

---

## The "Golden Rule"
Because this is the **Foundation**, it is very special:
1. **Everyone can use it:** Every other module in the project is allowed to "plug into" the Shared Kernel.
2. **Don't break it:** If you change something here, it affects the **entire** hotel. Be very careful!

## Critical Files to Know
- `RequestContextInterceptor.kt`: The guest's first stop.
- `SecurityConfig.kt`: The gatekeeper.
- `TenantContextFilter.kt`: The tenant identifier.
