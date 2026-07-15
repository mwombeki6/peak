#!/usr/bin/env python3
"""Exercise a populated hotel with real staff roles and deliberate failures."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable


Json = dict[str, Any] | list[Any] | None


class CheckFailed(RuntimeError):
    pass


@dataclass
class Result:
    name: str
    status: int
    expected: list[int]
    duration_ms: float
    error_code: str | None = None


class Http:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.results: list[Result] = []

    def request(
        self,
        name: str,
        method: str,
        path: str,
        *,
        token: str | None = None,
        body: Any = None,
        expected: int | Iterable[int] = 200,
        idempotency_key: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> Json:
        expected_values = [expected] if isinstance(expected, int) else list(expected)
        request_headers = {
            "Accept": "application/json",
            "X-Correlation-Id": f"real-hotel-{name}",
        }
        if token:
            request_headers["Authorization"] = f"Bearer {token}"
        if idempotency_key:
            request_headers["Idempotency-Key"] = idempotency_key
        if headers:
            request_headers.update(headers)
        data = None
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode()
            request_headers.setdefault("Content-Type", "application/json")
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=request_headers, method=method
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read()
        duration_ms = (time.perf_counter() - started) * 1000
        payload: Json = None
        if raw:
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"raw": raw.decode(errors="replace")[:2000]}
        error_code = payload.get("errorCode") if isinstance(payload, dict) else None
        self.results.append(Result(name, status, expected_values, duration_ms, error_code))
        if status not in expected_values:
            raise CheckFailed(
                f"{name}: {method} {path} returned {status}; expected "
                f"{expected_values}; body={json.dumps(payload)[:2000]}"
            )
        return payload


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailed(message)


def read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    require(isinstance(value, dict), f"Expected object in {path}")
    return value


def decimal(value: Any) -> Decimal:
    return Decimal(str(value))


def token_for(keycloak_url: str, username: str, password: str) -> str:
    data = urllib.parse.urlencode(
        {
            "grant_type": "password",
            "client_id": "peak-acceptance",
            "username": username,
            "password": password,
        }
    ).encode()
    request = urllib.request.Request(
        f"{keycloak_url.rstrip('/')}/realms/peak/protocol/openid-connect/token",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def delivered_invitation_token(provider_url: str, email: str) -> str:
    path = f"/v1/messages/latest?{urllib.parse.urlencode({'recipient': email})}"
    for _ in range(60):
        try:
            with urllib.request.urlopen(f"{provider_url.rstrip('/')}{path}", timeout=5) as response:
                message = json.load(response)
            content = message.get("content", "")
            for word in content.split():
                if word.startswith(("https://", "http://")):
                    token = urllib.parse.parse_qs(urllib.parse.urlparse(word).query).get("token", [None])[0]
                    if token:
                        return token
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        time.sleep(0.5)
    raise CheckFailed(f"Invitation for {email} was not delivered by the worker")


class KeycloakAdmin:
    def __init__(self, base_url: str, username: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        data = urllib.parse.urlencode(
            {
                "grant_type": "password",
                "client_id": "admin-cli",
                "username": username,
                "password": password,
            }
        ).encode()
        request = urllib.request.Request(
            f"{self.base_url}/realms/master/protocol/openid-connect/token",
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            self.token = json.load(response)["access_token"]

    def request(self, method: str, path: str, body: Any = None) -> Json:
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = urllib.request.Request(
            f"{self.base_url}/admin/realms/peak{path}",
            headers=headers,
            data=data,
            method=method,
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def ensure_user(self, username: str, email: str, password: str, full_name: str) -> None:
        matches = self.request(
            "GET", f"/users?username={urllib.parse.quote(username)}&exact=true"
        )
        require(isinstance(matches, list), "Keycloak users response was not a list")
        if matches:
            user_id = matches[0]["id"]
        else:
            first_name, _, last_name = full_name.partition(" ")
            self.request(
                "POST",
                "/users",
                {
                    "username": username,
                    "email": email,
                    "enabled": True,
                    "emailVerified": True,
                    "firstName": first_name,
                    "lastName": last_name or "Operations",
                },
            )
            matches = self.request(
                "GET", f"/users?username={urllib.parse.quote(username)}&exact=true"
            )
            require(isinstance(matches, list) and matches, f"Keycloak user {username} missing")
            user_id = matches[0]["id"]
        self.request(
            "PUT",
            f"/users/{user_id}/reset-password",
            {"type": "password", "value": password, "temporary": False},
        )


def provision_staff(
    http: Http,
    keycloak: KeycloakAdmin,
    keycloak_url: str,
    admin_token: str,
    tenant_id: str,
    property_id: str,
    run_id: str,
    password: str,
    provider_url: str,
) -> tuple[dict[str, dict[str, str]], str]:
    tenant_permissions = http.request(
        "tenant-permissions", "GET", f"/api/v1/tenants/{tenant_id}/permissions", token=admin_token
    )
    require(isinstance(tenant_permissions, list), "Tenant permission catalog unavailable")
    codes = {entry["code"] for entry in tenant_permissions}
    base_permission = "tenant.profile.view" if "tenant.profile.view" in codes else "tenant.roles.view"
    tenant_catalog_permissions = {
        "inventory.catalog.manage",
        "procurement.suppliers.manage",
    }
    require(tenant_catalog_permissions <= codes, "Tenant catalog permission set is incomplete")
    tenant_role_permissions = sorted({base_permission} | tenant_catalog_permissions)
    role = http.request(
        "create-staff-tenant-role",
        "POST",
        f"/api/v1/tenants/{tenant_id}/roles",
        token=admin_token,
        idempotency_key=f"real-hotel-tenant-role-{run_id}",
        body={
            "code": f"hotel_staff_{run_id.lower()}",
            "name": f"Real Hotel Staff {run_id}",
            "description": "Acceptance identities with property-scoped authority",
            "permissionCodes": tenant_role_permissions,
        },
    )
    require(isinstance(role, dict), "Tenant role response missing")
    tenant_role_id = role["tenantRoleId"]

    definitions = {
        "housekeeper": {
            "name": "Asha Housekeeper",
            "permissions": [
                "property.view", "housekeeping.view", "housekeeping.manage",
                "housekeeping.inspect", "lost_found.view", "lost_found.manage",
            ],
        },
        "maintenance": {
            "name": "Juma Technician",
            "permissions": [
                "property.view", "maintenance.view", "maintenance.manage",
                "maintenance.room_block",
            ],
        },
        "stores": {
            "name": "Neema Stores",
            "permissions": [
                "property.view", "inventory.view", "inventory.manage", "inventory.adjust",
                "procurement.view", "procurement.manage", "procurement.approve",
                "procurement.receive",
            ],
        },
        "restaurant": {
            "name": "Baraka Restaurant",
            "permissions": [
                "property.view", "pos.view", "pos.sessions", "pos.session.manage",
                "pos.orders", "pos.order.manage", "pos.order.settle", "pos.kitchen.view",
                "pos.kitchen.manage", "pos.item.void",
            ],
        },
        "supervisor": {
            "name": "Rehema Supervisor",
            "permissions": [
                "property.view", "housekeeping.view", "housekeeping.manage",
                "housekeeping.inspect", "maintenance.view", "maintenance.manage",
                "maintenance.room_block", "inventory.view", "procurement.view",
                "procurement.approve",
            ],
        },
    }
    staff: dict[str, dict[str, str]] = {}
    for department, definition in definitions.items():
        username = f"real-hotel-{department}-{run_id.lower()}"
        email = f"{username}@example.com"
        keycloak.ensure_user(username, email, password, definition["name"])
        identity_token = token_for(keycloak_url, username, password)
        invitation = http.request(
            f"invite-{department}",
            "POST",
            f"/api/v1/tenants/{tenant_id}/users/invitations",
            token=admin_token,
            expected=201,
            idempotency_key=f"real-hotel-invite-{department}-{run_id}",
            body={
                "email": email,
                "tenantRoleId": tenant_role_id,
                "fullName": definition["name"],
                "expiresInHours": 24,
            },
        )
        require(isinstance(invitation, dict), "Invitation response missing")
        invitation_token = invitation.get("invitationToken") or delivered_invitation_token(provider_url, email)
        accepted = http.request(
            f"accept-{department}",
            "POST",
            "/api/v1/invitations/accept",
            token=identity_token,
            idempotency_key=f"real-hotel-accept-{department}-{run_id}",
            body={"invitationToken": invitation_token, "fullName": definition["name"]},
        )
        require(isinstance(accepted, dict), "Invitation acceptance missing")
        user_id = accepted["userId"]
        property_role = http.request(
            f"role-{department}",
            "POST",
            f"/api/v1/tenants/{tenant_id}/properties/{property_id}/roles",
            token=admin_token,
            idempotency_key=f"real-hotel-property-role-{department}-{run_id}",
            body={
                "name": f"{definition['name']} {run_id}",
                "permissionCodes": definition["permissions"],
            },
        )
        require(isinstance(property_role, dict), "Property role response missing")
        http.request(
            f"assign-{department}",
            "POST",
            f"/api/v1/tenants/{tenant_id}/properties/{property_id}/users/{user_id}/roles/"
            f"{property_role['propertyRoleId']}/assign",
            token=admin_token,
            idempotency_key=f"real-hotel-property-assign-{department}-{run_id}",
        )
        staff[department] = {"userId": user_id, "token": token_for(keycloak_url, username, password)}
    return staff, tenant_role_id


def housekeeping_and_maintenance(
    http: Http, staff: dict[str, dict[str, str]], property_id: str, room_id: str, run_id: str
) -> dict[str, Any]:
    housekeeper = staff["housekeeper"]
    supervisor = staff["supervisor"]
    technician = staff["maintenance"]
    http.request(
        "housekeeping-settings", "PUT", f"/api/v1/properties/{property_id}/housekeeping/settings",
        token=housekeeper["token"], idempotency_key=f"real-hotel-hk-settings-{run_id}",
        body={"inspectionRequired": True, "stayoverEnabled": True, "stayoverIntervalDays": 3, "turnoverMinutes": 45},
    )
    task = http.request(
        "deep-clean-create", "POST", f"/api/v1/properties/{property_id}/housekeeping/tasks",
        token=housekeeper["token"], expected=201, idempotency_key=f"real-hotel-clean-{run_id}",
        body={"roomId": room_id, "type": "DEEP_CLEAN", "scheduledDate": date.today().isoformat(), "priority": 4,
              "notes": "Full post-departure clean and inspection"},
    )
    require(isinstance(task, dict), "Housekeeping task missing")
    task_id = task["id"]
    http.request("deep-clean-assign", "POST", f"/api/v1/properties/{property_id}/housekeeping/tasks/{task_id}/assign",
                 token=housekeeper["token"], idempotency_key=f"real-hotel-clean-assign-{run_id}",
                 body={"userId": housekeeper["userId"]})
    http.request("deep-clean-start", "POST", f"/api/v1/properties/{property_id}/housekeeping/tasks/{task_id}/start",
                 token=housekeeper["token"], idempotency_key=f"real-hotel-clean-start-{run_id}")
    completed = http.request("deep-clean-complete", "POST", f"/api/v1/properties/{property_id}/housekeeping/tasks/{task_id}/complete",
                             token=housekeeper["token"], idempotency_key=f"real-hotel-clean-complete-{run_id}",
                             body={"notes": "Linen changed, minibar checked, room cleaned"})
    require(isinstance(completed, dict) and completed["status"] == "AWAITING_INSPECTION", "Inspection was not enforced")
    http.request("reject-self-inspection", "POST", f"/api/v1/properties/{property_id}/housekeeping/tasks/{task_id}/inspect",
                 token=housekeeper["token"], expected=409, idempotency_key=f"real-hotel-self-inspect-{run_id}",
                 body={"passed": True, "notes": "Invalid self approval"})
    inspected = http.request("independent-inspection", "POST", f"/api/v1/properties/{property_id}/housekeeping/tasks/{task_id}/inspect",
                             token=supervisor["token"], idempotency_key=f"real-hotel-supervisor-inspect-{run_id}",
                             body={"passed": True, "notes": "Supervisor verified room readiness"})
    require(isinstance(inspected, dict) and inspected["status"] == "COMPLETED", "Independent inspection failed")

    lost = http.request("lost-item-record", "POST", f"/api/v1/properties/{property_id}/lost-and-found",
                        token=housekeeper["token"], expected=201, idempotency_key=f"real-hotel-lost-{run_id}",
                        body={"roomId": room_id, "description": "Guest wallet sealed in evidence bag",
                              "storageLocation": "Duty manager safe"})
    require(isinstance(lost, dict), "Lost-property record missing")
    claimed = http.request("lost-item-claim", "POST", f"/api/v1/properties/{property_id}/lost-and-found/{lost['id']}/claim",
                           token=housekeeper["token"], idempotency_key=f"real-hotel-lost-claim-{run_id}",
                           body={"reason": "Guest matched identity and described contents", "claimantDetails": "Acceptance guest"})
    require(isinstance(claimed, dict) and claimed["status"] == "CLAIMED", "Lost item claim failed")
    returned = http.request("lost-item-return", "POST", f"/api/v1/properties/{property_id}/lost-and-found/{lost['id']}/return",
                            token=housekeeper["token"], idempotency_key=f"real-hotel-lost-return-{run_id}",
                            body={"reason": "Wallet handed to verified guest", "claimantDetails": "Acceptance guest"})
    require(isinstance(returned, dict) and returned["status"] == "RETURNED", "Lost item return failed")

    request = http.request("maintenance-report", "POST", f"/api/v1/properties/{property_id}/maintenance/requests",
                           token=technician["token"], expected=201, idempotency_key=f"real-hotel-maint-request-{run_id}",
                           body={"roomId": room_id, "category": "HVAC", "description": "Air conditioner trips under load",
                                 "priority": "HIGH"})
    require(isinstance(request, dict), "Maintenance request missing")
    work_order = http.request("maintenance-work-order", "POST", f"/api/v1/properties/{property_id}/maintenance/work-orders",
                              token=technician["token"], expected=201, idempotency_key=f"real-hotel-work-order-{run_id}",
                              body={"requestId": request["id"], "roomId": room_id, "title": "Repair room air conditioner",
                                    "description": "Inspect breaker, compressor, and refrigerant", "priority": "high", "category": "HVAC"})
    require(isinstance(work_order, dict), "Work order missing")
    work_order_id = work_order["id"]
    http.request("maintenance-assign", "POST", f"/api/v1/properties/{property_id}/maintenance/work-orders/{work_order_id}/assign",
                 token=technician["token"], idempotency_key=f"real-hotel-maint-assign-{run_id}",
                 body={"userId": technician["userId"]})
    http.request("maintenance-start", "POST", f"/api/v1/properties/{property_id}/maintenance/work-orders/{work_order_id}/start",
                 token=technician["token"], idempotency_key=f"real-hotel-maint-start-{run_id}")
    block = http.request("room-block", "POST", f"/api/v1/properties/{property_id}/maintenance/rooms/{room_id}/blocks",
                         token=technician["token"], expected=201, idempotency_key=f"real-hotel-room-block-{run_id}",
                         body={"workOrderId": work_order_id, "type": "OUT_OF_ORDER", "reason": "HVAC unsafe for sale"})
    require(isinstance(block, dict), "Room block missing")
    http.request("maintenance-complete", "POST", f"/api/v1/properties/{property_id}/maintenance/work-orders/{work_order_id}/complete",
                 token=technician["token"], idempotency_key=f"real-hotel-maint-complete-{run_id}",
                 body={"reason": "Replaced failed breaker and completed load test"})
    verified = http.request("maintenance-verify", "POST", f"/api/v1/properties/{property_id}/maintenance/work-orders/{work_order_id}/verify",
                            token=supervisor["token"], idempotency_key=f"real-hotel-maint-verify-{run_id}")
    require(isinstance(verified, dict) and verified["status"] == "VERIFIED", "Work order was not verified")
    released = http.request("room-release", "POST", f"/api/v1/properties/{property_id}/maintenance/room-blocks/{block['id']}/release",
                            token=technician["token"], idempotency_key=f"real-hotel-room-release-{run_id}",
                            body={"reason": "Repair independently verified"})
    require(isinstance(released, dict) and released["status"] == "RELEASED", "Room block release failed")
    room = http.request("room-after-maintenance", "GET", f"/api/v1/properties/{property_id}/rooms/{room_id}", token=supervisor["token"])
    require(isinstance(room, dict) and room["status"] == "vacant_dirty", "Released room was incorrectly made sellable")
    http.request("maintenance-cannot-read-stock", "GET", f"/api/v1/properties/{property_id}/inventory/levels",
                 token=technician["token"], expected=403)
    return {"housekeepingTaskId": task_id, "lostAndFoundId": lost["id"], "workOrderId": work_order_id,
            "roomBlockId": block["id"], "releasedRoomStatus": room["status"]}


def inventory_procurement(
    http: Http, staff: dict[str, dict[str, str]], property_id: str, run_id: str
) -> dict[str, Any]:
    stores = staff["stores"]
    supervisor = staff["supervisor"]
    item = http.request("stock-item", "POST", f"/api/v1/properties/{property_id}/inventory/items",
                        token=stores["token"], expected=201, idempotency_key=f"real-hotel-stock-item-{run_id}",
                        body={"name": f"Premium Coffee {run_id}", "sku": f"COF-{run_id}", "category": "Food",
                              "unit": "kg", "reorderLevel": 5})
    source = http.request("main-store", "POST", f"/api/v1/properties/{property_id}/inventory/locations",
                          token=stores["token"], expected=201, idempotency_key=f"real-hotel-main-store-{run_id}",
                          body={"name": f"Main Store {run_id}", "type": "store"})
    target = http.request("receiving-store", "POST", f"/api/v1/properties/{property_id}/inventory/locations",
                          token=stores["token"], expected=201, idempotency_key=f"real-hotel-receiving-{run_id}",
                          body={"name": f"Receiving Store {run_id}", "type": "store"})
    require(isinstance(item, dict) and isinstance(source, dict) and isinstance(target, dict), "Inventory setup missing")
    http.request("opening-stock", "POST", f"/api/v1/properties/{property_id}/inventory/opening-balances",
                 token=stores["token"], idempotency_key=f"real-hotel-opening-stock-{run_id}",
                 body={"lines": [{"inventoryItemId": item["id"], "locationId": source["id"], "quantity": 20, "unitCost": 10000}],
                       "reason": "Verified opening stock count"})
    levels_before = http.request("levels-before-invalid-waste", "GET", f"/api/v1/properties/{property_id}/inventory/levels",
                                 token=stores["token"])
    require(isinstance(levels_before, list), "Stock levels missing")
    http.request("reject-negative-stock", "POST", f"/api/v1/properties/{property_id}/inventory/waste",
                 token=stores["token"], expected=409, idempotency_key=f"real-hotel-negative-stock-{run_id}",
                 body={"lines": [{"inventoryItemId": item["id"], "locationId": source["id"], "quantity": 21}],
                       "reason": "Impossible spoilage quantity"})
    levels_after = http.request("levels-after-invalid-waste", "GET", f"/api/v1/properties/{property_id}/inventory/levels",
                                token=stores["token"])
    require(levels_after == levels_before, "Rejected stock operation changed balances")
    transfer = http.request("stock-transfer", "POST", f"/api/v1/properties/{property_id}/inventory/transfers",
                            token=stores["token"], idempotency_key=f"real-hotel-transfer-{run_id}",
                            body={"sourceLocationId": source["id"], "destinationLocationId": target["id"],
                                  "lines": [{"inventoryItemId": item["id"], "quantity": 5}]})
    require(isinstance(transfer, dict) and len(transfer["movements"]) == 2, "Atomic transfer did not create paired movements")

    supplier = http.request("supplier", "POST", f"/api/v1/properties/{property_id}/procurement/suppliers",
                            token=stores["token"], expected=201, idempotency_key=f"real-hotel-supplier-{run_id}",
                            body={"name": f"Kilimanjaro Coffee Cooperative {run_id}", "code": f"KCC-{run_id}"})
    require(isinstance(supplier, dict), "Supplier missing")
    po = http.request("purchase-order", "POST", f"/api/v1/properties/{property_id}/purchase-orders",
                      token=stores["token"], expected=201, idempotency_key=f"real-hotel-po-{run_id}",
                      body={"supplierId": supplier["id"], "lines": [{"inventoryItemId": item["id"], "quantity": 10, "unitPrice": 12500}]})
    require(isinstance(po, dict), "Purchase order missing")
    po_id = po["id"]
    line_id = po["lines"][0]["id"]
    http.request("purchase-order-submit", "POST", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}/submit",
                 token=stores["token"], idempotency_key=f"real-hotel-po-submit-{run_id}")
    http.request("reject-self-approval", "POST", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}/approve",
                 token=stores["token"], expected=400, idempotency_key=f"real-hotel-po-self-approve-{run_id}")
    approved = http.request("independent-po-approval", "POST", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}/approve",
                            token=supervisor["token"], idempotency_key=f"real-hotel-po-approve-{run_id}")
    require(isinstance(approved, dict) and approved["approvedBy"] != approved["createdBy"], "PO separation of duties failed")
    http.request("partial-receipt", "POST", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}/receipts",
                 token=stores["token"], expected=201, idempotency_key=f"real-hotel-po-receipt-1-{run_id}",
                 body={"supplierReference": f"DELIVERY-A-{run_id}",
                       "lines": [{"purchaseOrderItemId": line_id, "locationId": target["id"], "quantity": 4}]})
    http.request("reject-over-receipt", "POST", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}/receipts",
                 token=stores["token"], expected=409, idempotency_key=f"real-hotel-po-over-receipt-{run_id}",
                 body={"supplierReference": f"DELIVERY-B-{run_id}",
                       "lines": [{"purchaseOrderItemId": line_id, "locationId": target["id"], "quantity": 7}]})
    http.request("final-receipt", "POST", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}/receipts",
                 token=stores["token"], expected=201, idempotency_key=f"real-hotel-po-receipt-2-{run_id}",
                 body={"supplierReference": f"DELIVERY-C-{run_id}",
                       "lines": [{"purchaseOrderItemId": line_id, "locationId": target["id"], "quantity": 6}]})
    final_po = http.request("received-purchase-order", "GET", f"/api/v1/properties/{property_id}/purchase-orders/{po_id}",
                            token=stores["token"])
    require(isinstance(final_po, dict) and final_po["status"] == "RECEIVED", "PO did not fully receive")
    final_levels = http.request("final-stock-levels", "GET", f"/api/v1/properties/{property_id}/inventory/levels",
                                token=stores["token"])
    require(isinstance(final_levels, list), "Final stock levels missing")
    relevant = [level for level in final_levels if level["itemId"] == item["id"]]
    require(sum(decimal(level["quantity"]) for level in relevant) == Decimal("30.000"), "Stock quantity is not conserved")
    require(sum(decimal(level["stockValue"]) for level in relevant) == Decimal("325000.00"), "Stock value is incorrect")
    return {"inventoryItemId": item["id"], "sourceLocationId": source["id"], "receivingLocationId": target["id"],
            "supplierId": supplier["id"], "purchaseOrderId": po_id, "quantity": "30.000", "stockValue": "325000.00"}


def restaurant(
    http: Http,
    staff: dict[str, dict[str, str]],
    property_id: str,
    outlet_id: str,
    menu_item_id: str,
    run_id: str,
) -> dict[str, Any]:
    token = staff["restaurant"]["token"]
    session = http.request(
        "restaurant-session", "POST", f"/api/v1/properties/{property_id}/pos-sessions/open",
        token=token, expected=201, idempotency_key=f"real-hotel-pos-session-{run_id}",
        body={"outletId": outlet_id, "openingFloat": 0},
    )
    require(isinstance(session, dict), "Restaurant POS session missing")
    session_id = session["id"]
    order = http.request("restaurant-order", "POST", f"/api/v1/properties/{property_id}/pos-orders",
                         token=token, expected=201, idempotency_key=f"real-hotel-pos-order-{run_id}",
                         body={"sessionId": session_id, "orderType": "dine_in", "clientOperationId": f"restaurant-order-{run_id}"})
    require(isinstance(order, dict), "POS order missing")
    order_id = order["id"]
    with_item = http.request("restaurant-order-item", "POST", f"/api/v1/properties/{property_id}/pos-orders/{order_id}/items",
                             token=token, idempotency_key=f"real-hotel-pos-item-{run_id}",
                             body={"menuItemId": menu_item_id, "quantity": 1, "clientOperationId": f"restaurant-item-{run_id}"})
    require(isinstance(with_item, dict) and len(with_item["items"]) == 1, "POS item missing")
    ticket = http.request("kitchen-send", "POST", f"/api/v1/properties/{property_id}/pos-orders/{order_id}/send",
                          token=token, idempotency_key=f"real-hotel-pos-send-{run_id}",
                          body={"clientOperationId": f"restaurant-send-{run_id}"})
    replay = http.request("kitchen-send-replay", "POST", f"/api/v1/properties/{property_id}/pos-orders/{order_id}/send",
                          token=token, idempotency_key=f"real-hotel-pos-send-{run_id}",
                          body={"clientOperationId": f"restaurant-send-{run_id}"})
    require(isinstance(ticket, dict) and isinstance(replay, dict) and replay["id"] == ticket["id"],
            "Offline kitchen replay created a duplicate ticket")
    ticket_id = ticket["id"]
    for action in ("prepare", "ready", "deliver"):
        transitioned = http.request(f"kitchen-{action}", "POST",
                                    f"/api/v1/properties/{property_id}/kitchen-tickets/{ticket_id}/{action}",
                                    token=token, idempotency_key=f"real-hotel-kitchen-{action}-{run_id}")
        require(isinstance(transitioned, dict), f"Kitchen {action} failed")
    settled = http.request("restaurant-settlement", "POST", f"/api/v1/properties/{property_id}/pos-orders/{order_id}/settle",
                           token=token, idempotency_key=f"real-hotel-pos-settle-{run_id}", body={"paymentMethod": "cash"})
    require(isinstance(settled, dict) and settled["status"] == "closed", "POS order did not settle")
    session_summary = http.request("restaurant-session-summary", "GET",
                                   f"/api/v1/properties/{property_id}/pos-sessions/{session_id}", token=token)
    require(isinstance(session_summary, dict), "Restaurant session summary missing")
    expected_cash = session_summary["session"]["expectedCash"]
    closed = http.request("restaurant-session-close", "POST",
                          f"/api/v1/properties/{property_id}/pos-sessions/{session_id}/close",
                          token=token, idempotency_key=f"real-hotel-pos-session-close-{run_id}",
                          body={"actualCash": expected_cash})
    require(isinstance(closed, dict) and closed["status"] == "closed", "Restaurant session did not close")
    return {"posSessionId": session_id, "posOrderId": order_id, "kitchenTicketId": ticket_id,
            "duplicateKitchenSendPrevented": True,
            "settlementMethod": "cash"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--keycloak-url", default="http://localhost:8081")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--tenant-password", required=True)
    parser.add_argument("--staff-password", required=True)
    parser.add_argument("--communication-provider-url", default="http://localhost:8090")
    parser.add_argument("--keycloak-admin", default=os.environ.get("KEYCLOAK_ADMIN", "admin"))
    parser.add_argument("--keycloak-admin-password", default=os.environ.get("KEYCLOAK_ADMIN_PASSWORD"))
    args = parser.parse_args()
    require(bool(args.keycloak_admin_password), "KEYCLOAK_ADMIN_PASSWORD is required")
    foundation = read_json(args.evidence_dir / "tenant-property-foundation.json")
    core = read_json(args.evidence_dir / "core-hospitality-journey.json")
    stay = read_json(args.evidence_dir / "stay-finance-foundation.json")
    tenant_id = foundation["tenantId"]
    property_id = core["propertyId"]
    room_id = foundation["roomId"]
    run_id = time.strftime("%Y%m%d%H%M%S", time.gmtime())
    http = Http(args.base_url)
    admin_token = token_for(args.keycloak_url, "acceptance-tenant-admin", args.tenant_password)
    keycloak = KeycloakAdmin(args.keycloak_url, args.keycloak_admin, args.keycloak_admin_password)
    staff, tenant_role_id = provision_staff(
        http, keycloak, args.keycloak_url, admin_token, tenant_id, property_id, run_id,
        args.staff_password, args.communication_provider_url
    )
    housekeeping = housekeeping_and_maintenance(http, staff, property_id, room_id, run_id)
    stock = inventory_procurement(http, staff, property_id, run_id)
    pos = restaurant(http, staff, property_id, stay["outletId"], stay["menuItemId"], run_id)
    checks = [result.__dict__ for result in http.results]
    require(all(result.status in result.expected for result in http.results), "At least one API check failed")
    evidence = {
        "journey": "real-hotel-department-operations",
        "result": "passed",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "tenantId": tenant_id,
        "propertyId": property_id,
        "runId": run_id,
        "staffIdentities": {name: {"userId": value["userId"]} for name, value in staff.items()},
        "tenantRoleId": tenant_role_id,
        "departments": ["front-office", "finance", "housekeeping", "maintenance", "stores", "procurement", "restaurant", "management"],
        "adversarialAssertions": [
            "housekeeper self-inspection rejected",
            "negative stock rejected atomically",
            "purchase-order self-approval rejected",
            "purchase over-receipt rejected",
            "maintenance release leaves room vacant dirty",
            "cross-department permission denied",
            "duplicate kitchen send consumed once",
        ],
        "housekeepingAndMaintenance": housekeeping,
        "inventoryAndProcurement": stock,
        "restaurant": pos,
        "apiChecks": checks,
        "requestCount": len(checks),
    }
    output = args.evidence_dir / "real-hotel-departments.json"
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (CheckFailed, urllib.error.URLError) as error:
        print(f"Real hotel department simulation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
