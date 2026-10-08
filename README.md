# LaundryOS API — IoT Backend

Spring Boot 4 backend for the Self-Service Laundry Operation System.  
Integrates with ESP32 washers (WM001/Wokwi) via EMQX Cloud MQTT over TLS.

---

## Architecture

```
ESP32 / Wokwi (WM001)
      ↕ MQTT over TLS (port 8883)
EMQX Cloud
      ↕ MQTT
Spring Boot Backend  ← this repo
      ↕
PostgreSQL
      ↕
REST API  /  WebSocket (STOMP)
      ↕
Web App / Mobile / Kiosk / Staff Dashboard
```

---

## Prerequisites

- Java 21
- Maven 3.9+
- Docker Desktop (for PostgreSQL)
- An EMQX Cloud account with a deployment

---

## Quick Start

### 1. Start PostgreSQL

```powershell
docker run -d `
  --name sslos-postgres `
  -e POSTGRES_DB=sslos `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=yourpassword `
  -p 5432:5432 `
  postgres:16
```

Or use the project's `docker-compose.yml` if present.

---

### 2. Configure Environment Variables

Copy `.env.example` to `.env` and fill in:

```env
# Database
DB_URL=jdbc:postgresql://localhost:5432/sslos
DB_USERNAME=postgres
DB_PASSWORD=yourpassword

# JWT
JWT_SECRET=your-very-long-random-secret-minimum-32-chars

# EMQX Cloud MQTT
MQTT_BROKER_HOST=your-cluster.emqxsl.com
MQTT_BROKER_PORT=8883
MQTT_USERNAME=your_mqtt_user
MQTT_PASSWORD=your_mqtt_password
MQTT_CLIENT_ID=sslos-backend-001
MQTT_USE_TLS=true
```

---

### 3. Run Spring Boot

Windows PowerShell:
```powershell
# Load env vars from .env file
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#][^=]+)=(.*)$') {
    [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2])
  }
}

.\mvnw.cmd spring-boot:run
```

Or use `run-be.ps1` if available.

The application starts on: `http://localhost:8080`

---

### 4. Verify Connection to EMQX

Check the application log for:
```
✅ Connected to EMQX broker at ssl://your-cluster.emqxsl.com:8883
✅ Subscribed to 5 topic groups
```

---

### 5. Verify Database Migrations

Flyway runs automatically on startup. The `machines` table should be seeded with WM001:

```sql
SELECT machine_code, status, online FROM machines;
-- Expected: WM001 | OFFLINE | false
```

---

### 6. Start Wokwi WM001

1. Open the Wokwi simulation for WM001.
2. Ensure the `diagram.json` has the EMQX broker host/credentials configured.
3. Start the simulation.
4. You should see heartbeat messages arrive in the backend log:
   ```
   [WM001] HEARTBEAT online=true uptimeMs=...
   ```

---

### 7. Observe Incoming MQTT Messages

Watch the Spring Boot log for:
```
MQTT ▶ [laundry/washer/00000000-0000-0000-0000-000000000001/WM001/heartbeat] {"machineId":"WM001","online":true,...}
[00000000-0000-0000-0000-000000000001/WM001] Machine came back online
[WebSocket] Broadcast machine update: WM001 → AVAILABLE on /topic/machines/WM001
```

---

### 8. Call REST Command API

```powershell
# First, get a JWT token
$loginResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" `
  -Method POST `
  -ContentType "application/json" `
  -Body '{"email":"admin@sslos.com","password":"yourpassword"}'

$token = $loginResponse.accessToken

# Issue START command (with a booking ID)
Invoke-RestMethod -Uri "http://localhost:8080/api/machines/WM001/commands" `
  -Method POST `
  -Headers @{Authorization="Bearer $token"} `
  -ContentType "application/json" `
  -Body '{"command":"START","bookingId":"your-booking-uuid"}'

# Issue ESTOP (emergency, no booking needed)
Invoke-RestMethod -Uri "http://localhost:8080/api/machines/WM001/commands" `
  -Method POST `
  -Headers @{Authorization="Bearer $token"} `
  -ContentType "application/json" `
  -Body '{"command":"ESTOP"}'
```

---

### 9. Verify Command Reaches Wokwi

In the Wokwi Serial Monitor you should see:
```
[MQTT] Received on laundry/washer/00000000-0000-0000-0000-000000000001/WM001/command
Command: START
```

The MQTT payload published by the backend:
```json
{
  "command": "START",
  "commandId": "CMD-uuid",
  "bookingId": "...",
  "issuedAt": "2026-..."
}
```

> **Backward compatibility**: The ESP32 firmware reads only the `"command"` field.  
> Unknown fields (`commandId`, `bookingId`, `issuedAt`) are silently ignored.

---

### 10. Observe Realtime WebSocket Updates

Connect a STOMP client to `ws://localhost:8080/ws` and subscribe to:
- `/topic/machines/WM001` — status changes
- `/topic/machines/WM001/telemetry` — telemetry stream
- `/topic/machines` — all machine updates

Using STOMP.js (browser):
```javascript
const client = new StompJs.Client({
  brokerURL: 'ws://localhost:8080/ws/websocket'
});
client.onConnect = () => {
  client.subscribe('/topic/machines/WM001', (msg) => {
    console.log(JSON.parse(msg.body));
  });
};
client.activate();
```

---

## Manual Testing with MQTTX

> MQTTX is for debugging only — not used in production.

> Replace `00000000-0000-0000-0000-000000000001` with the actual branchId UUID of your machine.

### Subscribe to WM001 topics:
- `laundry/washer/00000000-0000-0000-0000-000000000001/WM001/status`
- `laundry/washer/00000000-0000-0000-0000-000000000001/WM001/telemetry`
- `laundry/washer/00000000-0000-0000-0000-000000000001/WM001/heartbeat`
- `laundry/washer/00000000-0000-0000-0000-000000000001/WM001/event`

### Publish a simulated STATUS:
```json
Topic: laundry/washer/00000000-0000-0000-0000-000000000001/WM001/status
Payload:
{
  "machineId": "WM001",
  "status": "AVAILABLE",
  "weight": 0.00,
  "waterLevel": 0.0,
  "doorClosed": true,
  "leak": false,
  "error": ""
}
```

### Publish a simulated HEARTBEAT:
```json
Topic: laundry/washer/00000000-0000-0000-0000-000000000001/WM001/heartbeat
Payload:
{"machineId": "WM001", "online": true, "uptimeMs": 12345}
```

---

## API Reference

### Swagger UI
`http://localhost:8080/swagger-ui.html`

### Key Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| `GET`  | `/api/machines` | List all machines |
| `GET`  | `/api/machines/{code}` | Get machine details |
| `GET`  | `/api/machines/{code}/status` | Current status + telemetry |
| `GET`  | `/api/machines/{code}/events?page=0&size=20` | Event history |
| `GET`  | `/api/machines/{code}/commands?page=0&size=20` | Command history |
| `POST` | `/api/machines/{code}/commands` | Issue command |
| `POST` | `/api/auth/login` | Get JWT token |
| `POST` | `/api/auth/register` | Register user |

### Command Payload
```json
{
  "command": "START",
  "bookingId": "uuid-of-confirmed-booking"
}
```
Supported commands: `START`, `ESTOP`, `RESET`, `PAUSE`, `RESUME`, `CANCEL`

---

## State Transition Diagram

```
                         ┌──────────────────────────────────────────┐
                         │                                          │
    ┌──────────┐    ┌─────v──────┐    ┌──────────────┐    ┌────────┴───┐
    │ OFFLINE  │───▶│ AVAILABLE  │───▶│   RESERVED   │───▶│WAITING_START│
    └──────────┘    └─────┬──────┘    └──────────────┘    └────────────┘
         ▲                │                                     │
         │            (heartbeat                            (START cmd)
         │             timeout)                                  │
         │                │                                      ▼
    ┌────┴─────┐    ┌──────┴──────┐    ┌──────────┐    ┌──────────────┐
    │  ERROR   │◀───│   RUNNING   │───▶│  PAUSED  │    │   RUNNING    │
    └──────────┘    └─────────────┘    └──────────┘    └──────────────┘
                          │                                      │
                     (cycle done)                           (completed)
                          │                                      │
                    ┌─────▼──────┐                       ┌──────▼─────┐
                    │ COMPLETED  │──────────────────────▶│ AVAILABLE  │
                    └────────────┘                       └────────────┘
```

---

## Upgrading ESP32 to Support Command ACKs

When ready to add reliable command delivery:

1. **In firmware**: Parse `"commandId"` from the received JSON.
2. **In firmware**: After executing (or refusing) the command, publish to:
   ```
   laundry/washer/00000000-0000-0000-0000-000000000001/WM001/command/ack
   ```
   with payload:
   ```json
   {
     "commandId": "CMD-received-uuid",
     "machineId": "WM001",
     "command": "START",
     "result": "ACCEPTED",
     "reason": ""
   }
   ```
3. **Backend**: Already handles this — no backend changes needed.

---

## Security Notes

- JWT tokens expire in 15 minutes (refresh tokens: 7 days).
- MQTT credentials are **never** exposed to frontend clients.
- Machine GET endpoints are public (kiosk display use case).
- Machine POST/command endpoints require JWT authentication.
- `ESTOP` and `RESET` should be restricted to `ROLE_STAFF+` in Phase 9.
