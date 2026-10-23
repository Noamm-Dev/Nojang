# Nojang

Caching Mojang profile lookup server (Ktor Native, linuxX64), rotating outgoing requests across the host's public IPs.

## Endpoints

- `GET /{query}`: looks up a player by name or UUID (dashed or not). Returns `{ "name", "id" }`, or `404` if there's no such player.
- `POST /`: takes a JSON array of names and/or UUIDs (at most 400) and returns the players found, in request order. Misses are dropped.

Results and misses are cached in SQLite for 60 minutes.

## Configuration

- `DATABASE_PATH`: SQLite file path, defaults to `nojang.db`.

## Running

Ktor Native server doesn't support Windows, so build and run through Docker:

- `docker build -t nojang .`: builds the release image `nojang`.
- `docker run --rm --init --name nojang --network host nojang`: runs it on the host network (port 8080).
