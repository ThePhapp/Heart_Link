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

## Internal observability

Actuator health and metrics run on the separate management listener, defaulting to
`127.0.0.1:9091`. Only `health` and `metrics` are exposed, and health details are
hidden. Override `MANAGEMENT_SERVER_PORT` and `MANAGEMENT_SERVER_ADDRESS` only for
an internal monitoring network; the Docker Compose app does not publish this port.

Useful endpoints from the application host are `/actuator/health`,
`/actuator/metrics/http.server.requests`, and
`/actuator/metrics/hikaricp.connections.active` on the management listener.
Requests receive an `X-Request-ID` response header. Supplied IDs must be UUIDs;
other values are replaced before being added to the log context. Request IDs are
not metric dimensions. Custom metrics use `MetricPort` with closed event/outcome
enums, while `EventPublisherPort` is the application event boundary.
