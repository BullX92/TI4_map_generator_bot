# Eigener Original-Spielserver

Diese Installation verwendet den festgelegten Originalstand `a383a33` mit unseren Wrapper-Erweiterungen, Java 26 und PostgreSQL 16. Sie startet mit einer eigenen leeren Datenbank und einem eigenen Spielspeicher. Bestehende Installationen werden nicht eingebunden.

## Image und Start

Der Workflow `Wrapper original server` prüft Testbed und echte PostgreSQL-Persistenz und veröffentlicht danach ein eigenes amd64-Image in `ghcr.io/bullx92/ti4-wrapper-original`. Er läuft für `codex/**` und manuell. Das Image enthält auch die zum Quellstand passenden Karten, Daten und Schriftressourcen. Ein Checkout auf dem Zielserver ist nicht erforderlich.

1. `.env.example` nach `.env` kopieren und ausschließlich eigene Discord-Zugangsdaten sowie ein neues Datenbankpasswort eintragen.
2. `WRAPPER_IMAGE` auf den vollständigen SHA-Tag des erfolgreichen Builds setzen. Beispiel: `ghcr.io/bullx92/ti4-wrapper-original:sha-<vollständiger Commit>`.
3. Im Ordner `deploy` ausführen:

```sh
docker compose --env-file .env -f compose.yaml config --quiet
docker compose --env-file .env -f compose.yaml pull
docker compose --env-file .env -f compose.yaml up -d
docker compose --env-file .env -f compose.yaml ps
curl --fail http://127.0.0.1:18081/api/public/ping
curl --fail http://127.0.0.1:18081/api/public/ready
```

Die API ist zunächst nur am lokalen Netzwerkinterface erreichbar. Öffentlicher Zugriff erfolgt später über den geprüften Zugang mit Anmeldung. PostgreSQL veröffentlicht keinen Host-Port. Der Compose-Projektname isoliert Netzwerk und Volumes; kein fester Containername kollidiert mit bestehenden Diensten.

`ping` zeigt nur, ob HTTP antwortet. `ready` und ein echter Spielzug müssen zusätzlich geprüft werden. Für den Spielnachweis eine private Partie anlegen, eine Originalaktion ausführen, den Server mit `docker compose restart server` neu starten und die Partie erneut öffnen. Discord-Zugangsdaten sind dafür erforderlich.

## Was dauerhaft gespeichert wird

- `game-storage`: vollständige Original-Spielstände, Einstellungen und Undo-Dateien unter `/opt/STORAGE`.
- `postgres-data`: relationale Spiel-, Sitz-, Nachrichten- und Statistikdaten.

Beide Volumes gehören zum Nachweis und zur Sicherung. Die Original-Spielstände liegen weiterhin in Dateien; PostgreSQL allein ist kein vollständiges Backup. `docker compose down` behält die Daten. Keine `down -v`-Befehle für eine benutzte Installation verwenden. Vor Versionswechseln beide Speicher sichern und das vorherige Image behalten.

## Automatischer Persistenznachweis

`PostgresRestartPersistenceTest` verwendet die originalen Spring-Repositories und `GameEntityPersistenceService.replace`: Eine Partie mit Sitz und Titel wird gespeichert, der gesamte Spring-Kontext geschlossen und über einen neuen Kontext wieder gelesen. Der Test bestätigt PostgreSQL 16 und entfernt nur seine selbst erzeugten Datensätze. Er ersetzt keinen vollständigen Spielnachweis.

```sh
TI4_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/ti4_wrapper_test \
TI4_TEST_POSTGRES_USER=ti4_test TI4_TEST_POSTGRES_PASSWORD='<Testpasswort>' \
mvn -B -ntp -Dtest=PostgresRestartPersistenceTest test
```

Die Testdatenbank muss eigens dafür angelegt werden. Ohne `TI4_TEST_POSTGRES_URL` wird der Test übersprungen; der Workflow setzt sie ausdrücklich. Zugangsdaten niemals committen. Image und Compose enthalten keine offiziellen Discord-, AWS- oder Produktionszugänge.
