http://localhost:8080/api/v1/auth/login
http://localhost:8080/api/v1/auth/register
http://localhost:8080/api/v1/auth/refresh



Register:
{
  "email": "Mine@gmail.com",
  "password": "Mineeeeeeeeeeee1234!",
  "phone": "+77001234666",
  "first_name": "Hellosdfsdddddddddddddd",
  "last_name": "mesdffdsf",
  "push_consent": true,
  "role": "ADMIN"
}

Changepassword:
{
  "user_id": "7f6aed65-9a6e-4fa0-9066-831c76d22b04",
  "old_password": "Passdssssssssss1234!",
  "new_password": "Passme123456789?"
}

listusers:
{
  "pagination": {
    "page": 1,
    "page_size": 10,
    "sort_by": "created_at",
    "ascending": false
  }
}


Step 1 — Register as ADMIN
POST http://localhost:8080/api/v1/auth/register
{
  "email": "admin@courier.kz",
  "password": "Admin123!",
  "firstName": "Admin",
  "lastName": "User",
  "role": "ADMIN"
}
```

Save the `confirmationToken` from the response.

---

**Step 2 — Verify email**
```
GET http://localhost:8080/api/v1/auth/verify?token=<confirmationToken>
```

This is required — your auth-service likely rejects login for unverified emails.

---

**Step 3 — Login**
```
POST http://localhost:8080/api/v1/auth/login
{
  "email": "admin@courier.kz",
  "password": "Admin123!"
}

**4. Create Company**
```
POST http://localhost:8080/api/v1/companies
Authorization: Bearer <token>
{
  "name": "Courier Express LTD",
  "bin": "123456789012"
}

**4. Get All Companies**
```
GET http://localhost:8080/api/v1/companies
Authorization: Bearer <token>
```

**5. Create Employee**
```
POST  http://localhost:8080/api/v1/employees?companyId=2b3c3e37-9370-4b46-bfb6-a23270a06a2f
Authorization: Bearer <token>
{
  "email": "manager@courier.kz",
  "password": "Manager123!",
  "firstName": "Jane",
  "lastName": "Smith",
  "phone": "+77009876543",
}
```

---

**6. Create Address**
```
POST http://localhost:8080/api/v1/addresses
Authorization: Bearer <token>
{
  "companyId": "2b3c3e37-9370-4b46-bfb6-a23270a06a2f",
  "street": "Abay Avenue",
  "house": "10",
  "city": "Almaty",
  "country": "Kazakhstan",
  "postalCode": "050000"
}

docker exec -it courier-postgres psql -U courier_master -d auth_db -c "SELECT email, role, is_active, is_email_verified FROM public.users;"