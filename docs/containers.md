# One image, both applications

The Container images workflow discovers runnable standalone Gradle builds by their
settings/build files, plugins, wrappers and Java toolchain declarations. It finds
both the calculator application and the Salat Spring Boot API without a list of
project paths. Library builds are ignored. Unsupported or mixed Java configurations
fail explicitly. This detector supports this repository's Gradle layout; it is not
a general detector for arbitrary languages or Gradle multi-project builds.

Each discovered application runs its offline tests and Gradle packaging task.
The workflow assembles their outputs into one generated Docker context and one
`ghcr.io/cyonecoder/prayertime-calculator` image. No hand-maintained Dockerfile or
VPS build/publish token is needed. `.container-runtime.json` supplies runtime
arguments and ports because build manifests do not describe how a CLI should be
started as a persistent service.

The single container starts both processes as a non-root user:

- Calculator HTTP API: port 8080; CLI starts with `serve --port 8080`.
- Salat API: port 8081; executable Spring Boot jar.

Both share the container lifecycle: if either process exits, its sibling is
stopped and the container fails. SIGTERM stops both. Compose keeps ports on
localhost until an explicit proxy/public deployment is configured. Salat archives
persist in the `salat-data` volume.

CI smoke-tests both HTTP services and graceful shutdown before publishing.
Pushes to main publish `latest` and the full commit SHA. The bootstrap branch
`codex/part4-images` publishes only its full commit SHA for verification before
merge. Pull requests test without publishing. GitHub Actions authenticates using
its temporary GITHUB_TOKEN; the VPS retains only package-read credentials.

After a successful build, set APP_IMAGE to the published SHA tag, then:

```sh
docker pull "$APP_IMAGE"
docker compose --env-file /srv/lab/.env up -d
```

Run Compose from this checkout. Use `APP_IMAGE=... docker compose up -d` if the
central env does not yet contain APP_IMAGE. A successful pull proves availability;
it does not start the application. Inspect with `docker image ls` and `docker ps`.
The package is private by default on first publication; verify its visibility and
reader access in GitHub Packages. Merging enables the normal main/latest flow.
