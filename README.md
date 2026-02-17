# studySync (backend skeleton)

Spring Boot 3 + JWT auth + JPA/Hibernate + Liquibase.

## Run (H2 in-memory, default)
```bash
mvn spring-boot:run
```
Server: http://localhost:8080

## Run with PostgreSQL (optional)
1) Start Postgres (example via Docker):
```bash
docker run --name studysync-postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=studysync -p 5432:5432 -d postgres:16
```

2) Run with profile:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## Auth endpoints
- POST `/api/auth/register`
- POST `/api/auth/login`
- GET  `/api/auth/me` (requires Authorization: Bearer <token>)

Example register:
```bash
curl -X POST http://localhost:8080/api/auth/register       -H "Content-Type: application/json"       -d '{"email":"test@test.com","password":"Passw0rd!"}'
```

Example me:
```bash
curl http://localhost:8080/api/auth/me       -H "Authorization: Bearer <token>"
```
