# DepthWallpaper (CLOCKIT)

Android-Live-Wallpaper mit Tiefenwirkung: ein freigestelltes Objekt schwebt vor Uhrzeit und Datum.
Kotlin, Views + Material 3 — **kein Compose**.

## Bauen

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew assembleDebug
```

Das systemweite `JAVA_HOME` zeigt auf ein nicht vorhandenes Verzeichnis, deshalb muss es überschrieben
werden. Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`.

**AGP 9.2.1 bringt Kotlin bereits mit** — das Plugin `kotlin-android` darf nicht hinzugefügt werden,
sonst bricht der Build mit „extension already registered with name 'kotlin'" ab.

## Aufbau

| Bereich | Datei | Aufgabe |
|---|---|---|
| Zeichnen | `render/WallpaperRenderer.kt` | Malt alle Ebenen. Wird von Wallpaper, Editor-Vorschau und Dashboard-Miniaturen gemeinsam benutzt |
| Wallpaper | `wallpaper/DepthWallpaperService.kt` | Die Engine, die auf dem Gerät läuft |
| Editor | `editor/EditorActivity.kt` | Bearbeiten eines Presets |
| Übersicht | `dashboard/DashboardActivity.kt` | Startseite mit allen Presets, Selbst-Update |
| Vorschau | `view/WallpaperPreviewView.kt` | Direkte Bearbeitung per Finger |
| Daten | `data/PresetRepository.kt` | Presets, Zuordnung zu den Bildschirmen |
| Weichzeichner | `data/BlurUtils.kt` | Box-Blur mit Zwischenspeicher |

Positionen und Größen liegen als Bruchteile (0..1) der Leinwand vor, damit Vorschau und Gerät trotz
unterschiedlicher Auflösung identisch aussehen.

## Dinge, die nicht offensichtlich sind

**Sperrbildschirm und Hauptbildschirm teilen sich ein Live Wallpaper.** Android kann einem Wallpaper
keine getrennten Inhalte für beide geben. Die Engine fragt deshalb bei jedem Zeichnen den Keyguard,
ob gesperrt ist, und lädt die passende Konfiguration. Ein Standbild für den Sperrbildschirm wäre der
naheliegende Weg — er war auch mal umgesetzt und ist genau deshalb wieder verworfen worden: eine ins
Bild gerannte Uhrzeit kann sich nie aktualisieren.

**Die Uhr hängt an `ACTION_TIME_TICK`**, nicht an einem selbstgebauten Timer. Dazu läuft alle 15
Sekunden ein Wächter, der nur dann neu zeichnet, wenn sich der angezeigte Text geändert hat. Frühere
Fassungen planten den nächsten Tick erst *nach* dem Zeichnen — warf das Zeichnen eine Ausnahme, blieb
die Uhr für immer stehen. Das Neuplanen gehört deshalb in ein `finally`.

**`SimpleDateFormat` liegt pro Thread vor.** Renderer läuft aus Wallpaper-Engine, Editor und
Hintergrund-Threads gleichzeitig; eine geteilte Instanz liefert Unsinn oder wirft.

**Der Weichzeichner speichert Ergebnisse zwischen und gibt sie nie frei.** Mehrere Threads zeichnen
daraus, ein `recycle()` mitten in einem laufenden Zeichenvorgang würde abstürzen. Die Bitmaps sind
klein, der Speicherbereiniger räumt sie ab.

**Ziehen bewegt nur das bereits ausgewählte Element.** Vorher griff eine Wischbewegung, was gerade
unter dem Finger lag — überlappende Elemente klauten sich gegenseitig die Gesten.

## Selbst-Update

Die App prüft beim Start eine `update.json` unter einer Adresse, die im Zahnrad des Dashboards
eingetragen wird. Aktuell:

```
https://raw.githubusercontent.com/AntillaHO/CLOCKIT/main/update.json
```

`.github/workflows/build.yml` baut bei jeder Änderung an `main` die APK, schreibt die `update.json`
und legt beides ins Repo zurück.

**Die Versionsnummer kommt aus der Lauf-Nummer** (`100 + GITHUB_RUN_NUMBER`), gesetzt über die
Umgebungsvariable `VERSION_CODE`. Lokale Builds bleiben beim Standardwert in `app/build.gradle.kts`.

**Signiert wird mit dem ursprünglichen Debug-Schlüssel**, hinterlegt als Repository-Geheimnis
`ANDROID_KEYSTORE_BASE64`. Eine andere Signatur würde Android dazu bringen, das Update abzulehnen und
eine Deinstallation zu verlangen — alle gespeicherten Wallpaper wären weg. Der Schlüssel darf nie ins
Repository wandern.

## Sprache

Die Oberfläche ist durchgehend deutsch. Neue Texte gehören nach `res/values/strings.xml`.
