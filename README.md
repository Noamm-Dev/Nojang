# Nojang

Caching Mojang profile lookup server (Ktor Native, linuxX64), rotating outgoing requests across the host's public IPs.

## Endpoints

- `GET /{query}`: looks up a player by name or UUID (dashed or not). Returns `{ "name", "id" }`, or `404` if there's no such player.
- `POST /`: takes a JSON array of names and/or UUIDs (at most 400) and returns the players found, in request order. Misses are dropped. If Mojang fails or ratelimits part of the batch, those queries are dropped too and the response carries `X-Nojang-Incomplete: true`, so retry later.

Results and misses are cached in SQLite for 60 minutes.

## Configuration

- `DATABASE_PATH`: SQLite file path, defaults to `nojang.db`.
- `HOST`: address to bind, defaults to `0.0.0.0`.
- `PORT`: port to bind, defaults to `8080`.

## Running

Ktor Native server doesn't support Windows, so run it through Docker.

Every push to `main` publishes `ghcr.io/noamm-dev/nojang`, tagged `latest` and with the commit sha:

- `docker pull ghcr.io/noamm-dev/nojang:latest`: pulls the latest image.
- `docker run --rm --init --name nojang --network host -e DATABASE_PATH=/apps/files/nojang.db -v "$PWD/apps/files:/apps/files" ghcr.io/noamm-dev/nojang:latest`: runs it on the host network (port 8080), keeping the SQLite cache in `./apps/files/nojang.db` on the host.

Or build it yourself:

- `docker build -t nojang .`: builds the release image `nojang`, run it the same way with `nojang` as the image.