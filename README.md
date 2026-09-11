# SSLOS API - Authentication & Authorization Module

## Mục lục
- [Tổng quan](#tổng-quan)
- [Kiến trúc](#kiến-trúc)
- [Database Schema](#database-schema)
- [API Endpoints](#api-endpoints)
- [Cấu hình Environment](#cấu-hình-environment)
- [Security](#security)
- [Testing](#testing)

---

## Tổng quan

Module Authentication & Authorization được xây dựng trên:
- Java 21
- Spring Boot 4.1.1
- Spring Security
- JWT Authentication (JJWT 0.12.6)
- PostgreSQL + Flyway
- Lombok

### Các Roles được hỗ trợ

| Role | Mô tả |
|------|-------|
| `CUSTOMER` | Người dùng cuối - khách hàng sử dụng dịch vụ |
| `STAFF` | Nhân viên vận hành cửa hàng |
| `OWNER` | Chủ sở hữu/Quản lý cửa hàng |
| `ADMIN` | Quản trị hệ thống |

---

## Kiến trúc

```
Request → JwtAuthenticationFilter → SecurityContext → Controller → Service → Repository
```

### Package Structure

```
com.laundry
├── auth/
│   ├── controller/
│   │   └── AuthController.java          # Authentication endpoints
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   ├── LoginResponse.java
│   │   ├── RegisterRequest.java
│   │   ├── CustomerResponse.java
│   │   ├── RefreshTokenRequest.java
│   │   ├── RefreshTokenResponse.java
│   │   ├── ForgotPasswordRequest.java
│   │   ├── ResetPasswordRequest.java
│   │   ├── ChangePasswordRequest.java
│   │   ├── LogoutResponse.java
│   │   └── MessageResponse.java
│   ├── entity/
│   │   ├── RefreshToken.java
│   │   └── PasswordResetToken.java
│   ├── repository/
│   │   ├── RefreshTokenRepository.java
│   │   └── PasswordResetTokenRepository.java
│   ├── security/
│   │   ├── SecurityConfig.java
│   │   ├── JwtService.java
│   │   ├── JwtAuthenticationFilter.java
│   │   └── JwtAuthenticationEntryPoint.java
│   ├── AuthService.java
│   └── *Exception.java
│
├── user/
│   ├── controller/
│   │   └── UserController.java          # User profile endpoints
│   ├── dto/
│   │   ├── UserProfileResponse.java
│   │   └── UpdateProfileRequest.java
│   ├── entity/
│   │   ├── User.java
│   │   └── Role.java
│   ├── repository/
│   │   ├── UserRepository.java
│   │   └── RoleRepository.java
│   └── UserService.java
│
└── shared/
    ├── GlobalExceptionHandler.java
    ├── ApiError.java
    ├── EmailService.java
    └── OpenApiConfig.java
```

---

## Database Schema

### Tables

#### users
| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PRIMARY KEY |
| full_name | VARCHAR(120) | NOT NULL |
| email | VARCHAR(254) | NOT NULL, UNIQUE |
| phone | VARCHAR(16) | NOT NULL, UNIQUE |
| password_hash | VARCHAR(255) | NOT NULL |
| status | VARCHAR(20) | NOT NULL, CHECK (ACTIVE, INACTIVE, LOCKED, SUSPENDED) |
| created_at | TIMESTAMP WITH TIME ZONE | NOT NULL |
| updated_at | TIMESTAMP WITH TIME ZONE | NOT NULL |

#### roles
| Column | Type | Constraints |
|--------|------|-------------|
| id | BIGSERIAL | PRIMARY KEY |
| name | VARCHAR(50) | NOT NULL, UNIQUE |

#### user_roles
| Column | Type | Constraints |
|--------|------|-------------|
| user_id | UUID | PRIMARY KEY (FK → users.id) |
| role_id | BIGINT | PRIMARY KEY (FK → roles.id) |

#### refresh_tokens
| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PRIMARY KEY |
| user_id | UUID | NOT NULL (FK → users.id) |
| token | VARCHAR(255) | NOT NULL, UNIQUE |
| expires_at | TIMESTAMP WITH TIME ZONE | NOT NULL |
| revoked | BOOLEAN | NOT NULL, DEFAULT FALSE |
| created_at | TIMESTAMP WITH TIME ZONE | NOT NULL |

#### password_reset_tokens
| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PRIMARY KEY |
| user_id | UUID | NOT NULL (FK → users.id) |
| token | VARCHAR(255) | NOT NULL, UNIQUE |
| expires_at | TIMESTAMP WITH TIME ZONE | NOT NULL |
| used | BOOLEAN | NOT NULL, DEFAULT FALSE |
| created_at | TIMESTAMP WITH TIME ZONE | NOT NULL |

### Flyway Migrations

| Migration | Mô tả |
|-----------|-------|
| V1 | Tạo bảng users, roles, user_roles |
| V2 | Đảm bảo role CUSTOMER tồn tại |
| V3 | Seed tất cả roles (CUSTOMER, STAFF, OWNER, ADMIN) |
| V4 | Chuyển primary key sang UUID |
| V5 | Restore schema |
| V6 | Tạo bảng refresh_tokens |
| V7 | Tạo bảng password_reset_tokens |
| V8 | Mở rộng status enum (LOCKED, SUSPENDED) |

---

## API Endpoints

### Authentication Endpoints (`/api/auth`)

#### POST /api/auth/register
**Mục đích:** Đăng ký tài khoản Customer mới

**Request:**
```json
{
  "fullName": "Nguyen Van A",
  "email": "customer@example.com",
  "phone": "+84901234568",
  "password": "Password123!",
  "confirmPassword": "Password123!"
}
```

**Response (201 Created):**
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "fullName": "Nguyen Van A",
  "email": "customer@example.com",
  "phone": "+84901234568"
}
```

---

#### POST /api/auth/login
**Mục đích:** Đăng nhập và nhận JWT tokens

**Request:**
```json
{
  "email": "customer@example.com",
  "password": "Password123!"
}
```

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "abc123def456...",
  "customer": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "fullName": "Nguyen Van A",
    "email": "customer@example.com",
    "phone": "+84901234568"
  },
  "role": "CUSTOMER"
}
```

---

#### POST /api/auth/refresh
**Mục đích:** Làm mới Access Token sử dụng Refresh Token

**Request:**
```json
{
  "refreshToken": "abc123def456..."
}
```

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "newRefreshToken...",
  "tokenType": "Bearer"
}
```

**Security:** Sử dụng Refresh Token Rotation - token cũ bị revoke sau khi sử dụng.

---

#### POST /api/auth/logout
**Mục đích:** Đăng xuất và revoke tất cả Refresh Tokens

**Headers:** `Authorization: Bearer <accessToken>`

**Response (200 OK):**
```json
{
  "message": "Logged out successfully"
}
```

---

#### POST /api/auth/forgot-password
**Mục đích:** Gửi email hướng dẫn đặt lại mật khẩu

**Request:**
```json
{
  "email": "customer@example.com"
}
```

**Response (200 OK):**
```json
{
  "message": "If your email is registered in our system, you will receive password reset instructions."
}
```

**Security:** Luôn trả về message chung, không tiết lộ email có tồn tại hay không.

---

#### POST /api/auth/reset-password
**Mục đích:** Đặt lại mật khẩu sử dụng reset token

**Request:**
```json
{
  "token": "reset-token-from-email",
  "newPassword": "NewPassword123!",
  "confirmPassword": "NewPassword123!"
}
```

**Response (200 OK):**
```json
{
  "message": "Password has been reset successfully"
}
```

**Security:** Token chỉ sử dụng được một lần. Sau khi reset, tất cả Refresh Tokens bị revoke.

---

#### PUT /api/auth/change-password
**Mục đích:** Đổi mật khẩu cho user đã đăng nhập

**Headers:** `Authorization: Bearer <accessToken>`

**Request:**
```json
{
  "currentPassword": "OldPassword123!",
  "newPassword": "NewPassword123!",
  "confirmPassword": "NewPassword123!"
}
```

**Response (200 OK):**
```json
{
  "message": "Password changed successfully"
}
```

---

### User Profile Endpoints (`/api/v1/users`)

#### GET /api/v1/users/me
**Mục đích:** Lấy thông tin profile của user hiện tại

**Headers:** `Authorization: Bearer <accessToken>`

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "customer@example.com",
  "fullName": "Nguyen Van A",
  "phone": "+84901234568",
  "roles": ["CUSTOMER"],
  "status": "ACTIVE"
}
```

---

#### PUT /api/v1/users/me
**Mục đích:** Cập nhật thông tin profile

**Headers:** `Authorization: Bearer <accessToken>`

**Request:**
```json
{
  "fullName": "Updated Name",
  "phone": "+84909876543"
}
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "customer@example.com",
  "fullName": "Updated Name",
  "phone": "+84909876543",
  "roles": ["CUSTOMER"],
  "status": "ACTIVE"
}
```

**Lưu ý:** Không thể cập nhật `email`, `roles`, `status`, `password` qua endpoint này.

---

## Cấu hình Environment

### Environment Variables

| Variable | Mô tả | Default |
|----------|-------|---------|
| `DB_URL` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5432/sslos` |
| `DB_USERNAME` | Database username | `postgres` |
| `DB_PASSWORD` | Database password | (empty) |
| `JWT_SECRET` | JWT signing secret (min 32 bytes) | `change-this-development-secret-to-at-least-32-bytes` |
| `JWT_ISSUER` | JWT issuer claim | `sslos-api` |
| `JWT_EXPIRATION_SECONDS` | Access token expiration | `900` (15 phút) |
| `JWT_REFRESH_EXPIRATION_SECONDS` | Refresh token expiration | `604800` (7 ngày) |
| `PASSWORD_RESET_TOKEN_EXPIRATION` | Reset token expiration (seconds) | `900` (15 phút) |
| `CORS_ALLOWED_ORIGINS` | Allowed CORS origins (comma-separated) | `http://localhost:3000,http://localhost:8080` |

### Ví dụ .env file

```bash
# Database
DB_URL=jdbc:postgresql://localhost:5432/sslos
DB_USERNAME=postgres
DB_PASSWORD=your_password

# JWT
JWT_SECRET=your-super-secret-jwt-key-at-least-32-bytes-long
JWT_ISSUER=sslos-api
JWT_EXPIRATION_SECONDS=900
JWT_REFRESH_EXPIRATION_SECONDS=604800

# Auth
PASSWORD_RESET_TOKEN_EXPIRATION=900

# CORS
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173,https://your-frontend.com
```

---

## Security

### Password Security

- Mật khẩu được hash bằng BCrypt (strength 10)
- Yêu cầu mật khẩu:
  - Tối thiểu 8 ký tự
  - Ít nhất 1 chữ hoa
  - Ít nhất 1 chữ thường
  - Ít nhất 1 số
  - Ít nhất 1 ký tự đặc biệt
- Không bao giờ log hoặc trả về password hash

### JWT Security

- Access Token: 15 phút (configurable)
- Refresh Token: 7 ngày (configurable)
- Sử dụng HMAC-SHA256 signing
- Claims: `sub` (userId), `email`, `roles`, `iat`, `exp`, `iss`
- Refresh Token Rotation để prevent token reuse attacks

### User Status

| Status | Mô tả |
|--------|-------|
| `ACTIVE` | Tài khoản hoạt động bình thường |
| `INACTIVE` | Tài khoản không hoạt động |
| `LOCKED` | Tài khoản bị khóa (login bị từ chối) |
| `SUSPENDED` | Tài khoản bị đình chỉ |

### Authorization Rules

```
Public Endpoints:
├── POST /api/auth/register
├── POST /api/auth/login
├── POST /api/auth/refresh
├── POST /api/auth/forgot-password
├── POST /api/auth/reset-password
├── GET /swagger-ui/**
├── GET /v3/api-docs/**
└── GET /actuator/health

Authenticated Endpoints:
├── POST /api/auth/logout
├── PUT /api/auth/change-password
└── GET /api/v1/users/me
└── PUT /api/v1/users/me
```

---

## Testing

### Chạy Tests

```bash
# Chạy tất cả tests
mvn test

# Chạy với coverage report
mvn test jacoco:report

# Chạy specific test class
mvn test -Dtest=UserServiceTest
```

### Test Coverage

| Test Class | Mô tả |
|------------|-------|
| `JwtServiceTest` | JWT token creation và validation |
| `UserServiceTest` | Profile management và ownership checks |
| `AuthLogoutIntegrationTests` | Logout flow |
| `AuthRefreshTokenIntegrationTests` | Token refresh flow |
| `AuthPasswordResetIntegrationTests` | Password reset flow |
| `RegisterRequestValidationTests` | Registration validation |

### Test với Postman

**1. Register Customer:**
```http
POST http://localhost:8080/api/auth/register
Content-Type: application/json

{
  "fullName": "Test User",
  "email": "test@example.com",
  "phone": "+84901234568",
  "password": "Password123!",
  "confirmPassword": "Password123!"
}
```

**2. Login:**
```http
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "email": "test@example.com",
  "password": "Password123!"
}
```

**3. Get Profile (với Access Token):**
```http
GET http://localhost:8080/api/v1/users/me
Authorization: Bearer <accessToken>
```

**4. Refresh Token:**
```http
POST http://localhost:8080/api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "<refreshToken>"
}
```

**5. Logout:**
```http
POST http://localhost:8080/api/auth/logout
Authorization: Bearer <accessToken>
```

---

## Khởi tạo tài khoản Admin đầu tiên

### Development Environment

Để tạo tài khoản Admin đầu tiên cho development, chạy SQL trực tiếp:

```sql
-- Lấy role ADMIN
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN roles r
WHERE u.email = 'admin@example.com'
  AND r.name = 'ADMIN';
```

Hoặc sử dụng endpoint đăng ký thông thường để tạo Customer, sau đó assign ADMIN role thủ công.

### Production

**QUAN TRỌNG:** Không hardcode credentials trong production.

1. Sử dụng Bootstrap Admin qua environment variables
2. Hoặc tạo tài khoản qua secure admin panel
3. Hoặc sử dụng database migration với secure credentials từ vault

---

## Error Responses

Tất cả errors trả về format chuẩn:

```json
{
  "timestamp": "2026-09-11T10:00:00Z",
  "status": 400,
  "message": "Request validation failed",
  "fields": {
    "email": "Email must be valid"
  }
}
```

| HTTP Status | Mô tả |
|-------------|-------|
| 400 | Bad Request - Validation failed |
| 401 | Unauthorized - Invalid credentials hoặc token |
| 403 | Forbidden - Không có quyền truy cập |
| 409 | Conflict - Resource đã tồn tại |
| 500 | Internal Server Error |

---

## Future Improvements

- [ ] OAuth2/Google Login integration
- [ ] Two-Factor Authentication (2FA)
- [ ] Account lockout sau nhiều lần login thất bại
- [ ] Password history để prevent reuse
- [ ] Session management với Redis
- [ ] Rate limiting cho authentication endpoints
- [ ] Admin panel cho user management
