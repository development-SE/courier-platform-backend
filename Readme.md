Register:
{
  "email": "testemail@gmail.com",
  "password": "Passdssssssssss1234!",
  "phone": "+77001234567",
  "first_name": "Hellosdfsdddddddddddddd",
  "last_name": "mesdffdsf",
  "push_consent": true,
  "role": "CLIENT"
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
json{
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
json{
  "email": "admin@courier.kz",
  "password": "Admin123!"
}


docker exec -it courier-postgres psql -U courier_master -d auth_db -c "SELECT email, role, is_active, is_email_verified FROM public.users;"