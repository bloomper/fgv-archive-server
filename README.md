# Spexarkivet Server

## Overview

Spexarkivet Server is the backend of Spexarkivet, FGV's archive of the spex' manuscripts and musical arrangements,
with their versions and files. It is a Spring Boot application with a GraphQL API and plain HTTP endpoints for files
(with byte ranges, for reading PDFs in the browser), previews, zips, share links and resumable (tus) uploads. A worker,
the same image with `API_WORKER_ENABLED=true`, reads the files' page counts and text for search with PDFBox and Apache
Tika and renders their first-page previews. It is backed by MySQL, a storage folder and Keycloak ([fgv-keycloak][1]) for
authentication, and reads spex from Spexregister. [Spexarkivet Web][2] is its frontend and [fgv-archive-deploy][3] runs
it on a server.

The product spec is in [fgv-archive-spec][4].

## Learning more

To learn more about Spexarkivet Server, please consult the reference documentation, which is served at `/docs`. When
running locally, the reference documentation is available [here][10]. Its source is in `src/main/asciidoc`, and
`getting-started.adoc` describes how to run the whole stack locally with `docker compose`.

## Building from source

You will need Java 25 or later to build Spexarkivet Server, and Docker for the integration tests. It is built using
[Gradle][5]:

```
./gradlew test              # unit tests
./gradlew integrationTest   # integration tests, against MySQL and Keycloak in Testcontainers
./gradlew build             # both, plus the reference documentation
```

## Published images

CI builds the image with Jib and publishes `bloomper/fgv-archive-server` for `linux/amd64` and `linux/arm64`.
Jib builds on `bloomper/fgv-archive-server-base:25` (`eclipse-temurin:25-jre` plus fonts for PDFBox, from
`base/Dockerfile`), which CI rebuilds and publishes first. The same image runs as API and as worker:

| Trigger              | Tags                              |
|----------------------|-----------------------------------|
| push to `develop`    | `develop`, `sha-<commit>`         |
| push of tag `vX.Y.Z` | `X.Y.Z`, `latest`, `sha-<commit>` |

To release, set `version` in `build.gradle` to `X.Y.Z`, merge to `main`, then `git tag vX.Y.Z && git push origin vX.Y.Z`.
The build fails if the tag doesn't match the version or if `X.Y.Z` is already on Docker Hub.

## Contributing

Contributors to this project agree to uphold its [code of conduct][6].

You can contribute to Spexarkivet Server by:

- Opening a [pull request][7]. Please see the [contributor guidelines][8] for details

## License

Spexarkivet Server is open source software released under the [Apache 2.0 license][9].

[1]: https://github.com/bloomper/fgv-keycloak
[2]: https://github.com/bloomper/fgv-archive-web
[3]: https://github.com/bloomper/fgv-archive-deploy
[4]: https://github.com/bloomper/fgv-archive-spec
[5]: https://gradle.org
[6]: CODE_OF_CONDUCT.md
[7]: https://help.github.com/articles/using-pull-requests/
[8]: CONTRIBUTING.md
[9]: https://www.apache.org/licenses/LICENSE-2.0.html
[10]: https://api.archive.fgv.local.nu:8443/docs/index.html
