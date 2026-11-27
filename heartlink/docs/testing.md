# Test workflow

Unit tests do not require PostgreSQL:

```powershell
.\mvnw.cmd -B test
```

Integration tests use only the disposable PostgreSQL service on `127.0.0.1:55432`.
Start it and run the required full gate with:

```powershell
docker compose -f docker-compose.test.yml up -d --wait
.\mvnw.cmd -B verify
```

`verify` runs unit tests and every `*IT` integration-test class. If the test database
is unavailable, integration tests fail the build instead of being skipped. The test
Spring profile pins application integration tests to `heartlink_test`; do not point
it to a development or shared database.

Reports are written to `target/surefire-reports` for unit tests and
`target/failsafe-reports` for integration tests. Stop the disposable database with:

```powershell
docker compose -f docker-compose.test.yml down
```
