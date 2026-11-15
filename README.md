# DoorZign-App (Android)

Native App (Kotlin, Jetpack Compose, Material 3) für **DoorZign Edge** und **Zeitwart Cloud** über den
gemeinsamen Vertrag `/mobile/v1`. Vertrag, UI-Spezifikation und Entscheidungen liegen in
`../doorzign-app-coordination` – Änderungen am Vertrag nur per Change Request an die Koordination.

## Module
| Modul | Inhalt |
|---|---|
| `api` (JVM) | Generierter Client (openapi-generator, `jvm-okhttp4`, kotlinx.serialization) aus `contract/dist/mobile-v1.yaml` + Kern: Session, Token-Refresh (`TokenAuthenticator`), CA-Pinning (`PinningTrustManager`), QR-Parser, PKCE, Fehlerformat, Multipart-Upload |
| `app` (Android) | Compose-UI, Keystore-Tokenablage, Sitzungsverwaltung, mDNS (NsdManager), QR (CameraX + ML Kit), SSO (Custom Tabs) |

Von Hand statt generiert: `SlotContent` (sealed class), `TextContent` (immer mit `type`), `POST /media` und
`POST …/preview` (`RawCalls.kt`). Der generierte Serializer wird auf `explicitNulls = false` gesetzt.

## Bauen
```bash
./build.sh :api:test               # Kern-Tests (JVM)
./build.sh :app:assembleDebug      # APK: app/build/outputs/apk/debug/
```
Der Build läuft im Docker-Image `doorzign-build:1` (aus `../doorzign-edge/docker`) mit eigenem Gradle-Volume.

## Gegen den Mock entwickeln
```bash
docker run -d --rm --name dz-app-android-prism --platform linux/amd64 -p 4011:4010 \
  -v ../doorzign-app-coordination/contract:/c:ro stoplight/prism:4 mock -h 0.0.0.0 /c/dist/mobile-v1.yaml
PRISM_URL=http://host.docker.internal:4011 ./build.sh :api:test   # Smoke-Test aller Endpunkte
```
Die Debug-App bietet im Onboarding „Mock-Server (Entwicklung)“ (`BuildConfig.MOCK_ORIGIN`, Emulator: `http://10.0.2.2:4010`).

## Grundregeln (aus docs/ui-spec.md)
- Genau ein aktives Profil; Systemwechsel/Abmelden löscht Tokens, Cache (`cacheDir/instances/<hash>`) und Profil.
- Freischaltung nur über `System.features` und `Display.level`, nie über `systemType`.
- 401 `token_expired` → ein serialisierter Refresh; jeder andere 401 → Anmeldung; 503/Netzfehler beim Refresh → Sitzung bleibt.
