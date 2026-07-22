# Project workflow

- Develop, build, run, and test this project locally.

## Tests

`./gradlew build` runs everything that needs no external service.

The MySQL compatibility suite is opt-in, because it needs a real server:

```bash
docker run --rm -d --name idle-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=idle -e MYSQL_DATABASE=idle_test mysql:8.4

IDLE_TEST_MYSQL=true IDLE_MYSQL_PASSWORD=idle \
  IDLE_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL=true \
  ./gradlew test --tests '*MySqlIntegrationTest*'
```

Without `IDLE_TEST_MYSQL=true` the suite disables itself and the build still
passes, so a green run is not evidence that MySQL was exercised — check for
`skipped="0"` in
`build/test-results/test/TEST-dev.branzx.idle.storage.MySqlIntegrationTest.xml`.
CI does exactly that (`.github/workflows/ci.yml`).
