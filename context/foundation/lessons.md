# Lessons Learned

> Append-only register of recurring rules and patterns. Re-read at start by /10x-frame, /10x-research, /10x-plan, /10x-plan-review, /10x-implement, /10x-impl-review.

## On Linux, host.docker.internal does not resolve inside containers

- **Context**: Docker local verification step — any phase that runs containers locally to smoke-test the app before deploying
- **Problem**: On Linux (unlike macOS/Windows), `host.docker.internal` is not automatically injected into container DNS. The app container throws `java.net.UnknownHostException: host.docker.internal`, Hikari fails to connect, and `/actuator/health` reports `db: DOWN` with no obvious reason in the surface output.
- **Rule**: On Linux, never rely on `host.docker.internal` to reach a sibling container. Put both containers on a shared Docker network (`docker network create`) and reference the DB container by its `--name`. Alternatively, pass `--add-host=host.docker.internal:host-gateway` explicitly to each container that needs host resolution.
- **Applies to**: plan, implement
