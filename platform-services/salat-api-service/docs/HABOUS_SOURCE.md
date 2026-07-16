# Habous official source — TLS and certificate chain

The service collects official prayer times from the Moroccan Habous endpoint:

```
https://www.habous.gov.ma/prieres/horaire_hijri_2.php?ville={id}
```

## Why `curl` works but Java failed

The Habous server presents an **incomplete TLS certificate chain**: it sends only
its leaf certificate and omits the Sectigo intermediate that issued it.

- `curl` (and browsers) succeed because most OpenSSL / OS trust stores either
  already cache that popular Sectigo intermediate or fetch it via the leaf's
  *Authority Information Access* extension.
- The JDK's default `HttpClient` does **not** do AIA fetching and does not have
  the intermediate cached, so it cannot build a path from the leaf to a trusted
  root and the handshake fails:

  ```
  javax.net.ssl.SSLHandshakeException: PKIX path building failed:
    sun.security.provider.certpath.SunCertPathBuilderException:
    unable to find valid certification path to requested target
  ```

Confirmed from this repo with the default client:

```
default JDK client: SunCertPathBuilderException:
  unable to find valid certification path to requested target
```

## The fix: augment trust, never disable it

`HabousTlsConfiguration` builds an `HttpClient` (`@Qualifier("habousHttpClient")`)
whose `SSLContext` uses a **composite** `X509TrustManager` (`HabousTrustStore`):

1. The platform default trust manager is tried first — nothing the JVM already
   trusts changes.
2. Only if the platform manager rejects the chain does a second trust manager,
   seeded with the **bundled Sectigo intermediate**, get a chance to build the
   path.

The bundled intermediate lives inside this module:

```
src/main/resources/truststore/sectigo-public-server-auth-ca-dv-r36.pem
```

This is fundamentally different from disabling verification:

- hostname verification is **on** (default `HttpClient` behaviour, untouched);
- certificate validity, expiry and signature checks are **on**;
- an unrelated, forged or expired certificate is **still rejected** (proved by
  `HabousTrustStoreTest.anUnrelatedSelfSignedCertificateIsNotBlindlyAccepted`);
- there is **no** trust-all `X509TrustManager`, no `-Dcom.sun.net.ssl` bypass, and
  the global JVM trust store is **not** modified;
- the certificate is **bundled**, never downloaded at runtime.

We supply exactly the one publicly-issued intermediate the server should have sent
itself. We are completing the chain the server omitted, not trusting something new.

## Bundled certificate provenance

| Field | Value |
|---|---|
| Subject | `CN=Sectigo Public Server Authentication CA DV R36, O=Sectigo Limited, C=GB` |
| Issuer | `CN=Sectigo Public Server Authentication Root R46, O=Sectigo Limited, C=GB` |
| Serial | `397A66CC2756362E0DAA87CA6EABE3B1` |
| Not after | `2036-03-21` |
| SHA-256 (file) | `b40358316fd9e1caaaab92c382927d2c13ef51b481eec9371a82e16c86f2a687` |

It was copied verbatim from the reviewed research resource
`src/main/resources/research/truststore/…` and is a well-known, publicly-issued
Sectigo intermediate — not a self-signed or invented certificate.

## Certificate rotation — what to do when it changes

Do **not** assume this leaf/intermediate lasts forever. If Habous rotates to a
different issuer:

- collection will fail again with `TLS_HANDSHAKE_FAILED`, and the per-run summary
  will make that obvious (see below);
- inspect the live chain:

  ```
  openssl s_client -connect www.habous.gov.ma:443 -showcerts </dev/null \
    | openssl x509 -noout -issuer -subject -enddate
  ```

- if the new intermediate is a different, still publicly-issued Sectigo (or other
  CA) intermediate, **review it**, then replace the bundled PEM and update the
  provenance table above and `HabousTrustStoreTest` (which pins the serial, so a
  silent swap fails the build);
- never react by disabling verification.

`HabousTrustStoreTest` pins the bundled certificate's serial number, so if the
file is replaced without updating the test the build fails — a deliberate tripwire.

## Diagnostics

A fetch failure now names the real cause, not a generic "failed to reach":

```
Failed to reach Habous for city 1 via www.habous.gov.ma/prieres/horaire_hijri_2.php:
  SSLHandshakeException: PKIX path building failed …
```

Each failing city logs a concise `WARN` with its `NetworkFailureCategory`; the full
stack trace goes to `DEBUG` (so one shared cause does not print 191 stack traces);
and the run ends with a grouped summary:

```
191 cities failed
 - TLS_HANDSHAKE_FAILED: 191
```

Categories: `TLS_HANDSHAKE_FAILED`, `DNS_FAILED`, `CONNECT_TIMEOUT`,
`REQUEST_TIMEOUT`, `HTTP_403`, `HTTP_429`, `HTTP_5XX`, `INTERRUPTED`,
`UNKNOWN_NETWORK_ERROR`.

## Running the one-city live test

The normal suite never touches the network. To validate TLS against the real
endpoint, run the opt-in live test:

```
./gradlew liveTest
# or
./gradlew test --tests "*HabousClientLiveTest"
```

It fetches only Rabat (ville 1) and asserts a structurally valid timetable came
back over a validated connection. It does not assert exact times or a specific
Hijri month.

## Disabling the scheduler during development

The daily collection scheduler is on by default. Turn it off with the property or
its environment variable (Spring relaxed binding):

```
SALAT_SCHEDULING_ENABLED=false ./gradlew bootRun
# or
./gradlew bootRun --args='--salat.scheduling.enabled=false'
```

The property is `salat.scheduling.enabled`; the cron is `salat.habous.sync-cron`
(default `0 15 3 * * *`, `Africa/Casablanca`). Do **not** use `HABOUS_SYNC_ENABLED`
— it is not a supported alias. A one-shot manual job (`--salat.job=…`) also
disables the scheduler automatically so the CLI can exit.

## The `ville` parameter is the only parameter

The official page accepts only `ville`; there is no month/year selector, so
historical months cannot be requested. Archives are built forward from what the
site publishes "now" (the current Hijri month, which spans two Gregorian months).
