# Peak Integrations Service 

Welcome to the **Integrations Service**! This part of the Peak system is like a "bridge" that connects our internal booking system to the outside world—specifically to payment providers in Tanzania (like M-Pesa, Tigo Pesa) and various banks.

## What does this service do?

Imagine a guest wants to book a room at a hotel using your app. They've picked their dates and room. Now comes the important part: **Paying for it.**

This service handles:
1.  **Public Booking Sessions:** Creating a temporary "slot" for a guest to book.
2.  **Payment Integration:** Sending the bill to Vodacom, Airtel, or a Bank so the guest can pay using their phone or account.

---

##  The Payment Flow 

How a payment happens in this system, step-by-step:

1.  **The Request:** The mobile app sends a request saying: *"Hey, Session #123 wants to pay 50,000 TZS via M-Pesa using phone 074xxxxxxx"*.
2.  **The Bridge:** Our `PublicPaymentController` hears this and tells the `PaymentIntegrationService` to start working.
3.  **The Record:** We save a record in our database (`payment_transactions`) so we never "forget" that this person tried to pay. We mark it as `PENDING`.
4.  **The Talk:** We talk to Vodacom's servers. We tell them: *"Please ask the person with this phone number for 50k"*.
5.  **The PIN:** The guest gets a popup on their phone asking for their M-Pesa PIN.
6.  **The Success:** Once they enter the PIN, Vodacom tells us *"It's done!"*, and we update our record to `COMPLETED`.

---

##  Supported Providers

We have already set up the "address book" for the following providers in Tanzania:

| Provider | Type | Config Key |
| :--- | :--- | :--- |
| **Vodacom M-Pesa** | Mobile Money | `vodacom-mpesa` |
| **Tigo Pesa** | Mobile Money | `tigo-pesa` |
| **Airtel Money** | Mobile Money | `airtel-money` |
| **Halopesa** | Mobile Money | `halopesa` |
| **AzamPesa** | Mobile Money | `azampesa` |
| **NMB** | Bank | `nmb` |
| **CRDB** | Bank | `crdb` |
| **NBC** | Bank | `nbc` |

---

## 🛠 How to Configure (The "Secret Keys")

To actually make it work, the hotel/company needs to sign a contract with these providers. They will give you an **API Key** and an **API Secret**.

You put these in your `application.yaml` or as Environment Variables:

```yaml
peak:
  integrations:
    payment:
      providers:
        vodacom-mpesa:
          base-url: "https://api.vodacom.co.tz/v1/payment"
          api-key: "YOUR_VODACOM_KEY"
          api-secret: "YOUR_VODACOM_SECRET"
```

---

##  Important Notes

*   **Commercial Agreement:** You **MUST** have a merchant account with the providers. The code is ready, but the providers won't allow us to talk to them without an account.
*   **Security:** Never share your `api-key` or `api-secret` on GitHub or with anyone else.
*   **Database:** All payments are tracked in the `payment_transactions` table. If a guest says "I paid but my booking is still pending", you can check this table using the `reference_id`.
*   **Idempotency (Double-Payment Protection):** To prevent charging a guest twice if they click "Pay" too many times, send the `Idempotency-Key` header with your request. The system will recognize the duplicate and return the previous response without initiating a new payment.

---

##  API Endpoints

### 1. Initiate a Payment
`POST /api/v1/public/payments/initiate`

**Example Request:**
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "provider": "VODACOM_MPESA",
  "paymentMethod": "MOBILE_MONEY",
  "phoneNumber": "255740000000",
  "amount": 50000.0
}
```

**Example Response:**
```json
{
  "referenceId": "PAY-A1B2C3D4",
  "status": "PENDING",
  "message": "Payment initiated successfully. Please check your phone for the PIN prompt."
}
```
