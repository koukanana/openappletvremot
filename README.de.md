# Xiaomi Bluetooth-Fernbedienung × Android TV

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [繁體中文](README.zh-TW.md) · **Deutsch**

> Alle Tasten der Xiaomi Bluetooth-Fernbedienung (2 / 2 Pro) auf jedem Android TV. Auf Sony BRAVIA verifiziert, kein Root nötig.

CleanYourTV verpasst Android TV eine Oberfläche im Apple-Stil – aber nicht das Gefühl in der Hand. Die Fernbedienung des Apple TV kostet einzeln über 200 Euro, und die nächste Hardware im Android-TV-Umfeld ist die Xiaomi Bluetooth-Fernbedienung 2 / 2 Pro: 99 Yuan neu, gebraucht in neuwertigem Zustand etwa 60 Yuan.

Von Haus aus sprechen die beiden nicht miteinander: Xiaomi garantiert seine Fernbedienungen nur für Xiaomi-Fernseher, und Sonys Bluetooth-Stack akzeptiert ausschließlich Originalzubehör. Dieses Repository reißt diese Mauer ein.

<table>
<tr>
<td align="center"><img src="docs/images/before-sony-remote.jpg" width="280"><br><sub><b>Before</b> · Sony-Original</sub></td>
<td align="center"><img src="docs/images/after-xiaomi-remote-2pro.jpg" width="280"><br><sub><b>After</b> · Xiaomi Bluetooth-Fernbedienung 2 Pro</sub></td>
</tr>
</table>

## Was funktioniert

| Taste | Status | Hinweis |
|---|---|---|
| Steuerkreuz / OK / Zurück | ✅ Nativ | Standard-HID-Tastaturcodes, direkt nach dem Pairing nutzbar |
| Lautstärke / Ein/Aus | ✅ Nativ | Wie oben |
| Home | ⚠️ Anpassung nötig | Die Fernbedienung sendet `KEYCODE_MOVE_HOME(122)` – die Taste „an den Zeilenanfang“ in Textfeldern. Auf einem Fernseher gibt es keine Textfelder, also passiert nichts |
| TV | ⚠️ Anpassung nötig | Sendet `KEYCODE_GRAVE(68)`, ebenfalls eine Taste, auf die niemand hört. Dieses Projekt belegt sie mit „Eingang wechseln“ |
| Menü | ✅ | Firmware-Abhängig, meist nativ |
| Sprache | ❓ Ungeprüft | Der Keycode ist auslesbar (`KEYCODE_F5(135)`), aber das Gerät hat kein eingebautes Mikrofon, deshalb funktioniert die Audioaufnahme nicht. Details unten |

Nach dem Fix: **Steuerkreuz, OK, Zurück, Home, Lautstärke, Menü, Ein/Aus und TV (auf Eingangswahl umgelegt) funktionieren alle, und die Fernbedienung verbindet sich nach einem Neustart selbst wieder.**

## Die drei Mauern (= das ganze Projekt)

| Was man vermutet | Was tatsächlich los ist |
|---|---|
| Die Xiaomi-Fernbedienung läuft nur an Xiaomi-Fernsehern | Sie spricht Standard-BLE-HID (HOGP), jeder Bluetooth-HID-Host kann sie empfangen. Wirklich an Xiaomi gebunden sind nur die Sprachtaste und einige Sondertasten |
| Sony hat eine Bluetooth-Whitelist, deshalb findet die Suche nichts | Blockiert ist nicht der Stack, sondern **der Bildschirm „Zubehör hinzufügen“ in den Einstellungen** – er filtert nach Gerätetyp, deshalb erscheinen Fremd-Fernbedienungen nicht. Der Stack scannt die ganze Zeit weiter |
| Pairing klappt, danach reagiert nichts | Android 9 hat `BluetoothHidHost.connect()` / `setPriority()` auf die Sperrliste der versteckten APIs gesetzt. Normale Reflection wirft `NoSuchMethodException` – das sieht exakt so aus, als hätte Sony die Methode entfernt. Ein vorheriger Aufruf von `VMRuntime.setHiddenApiExemptions("L")` lässt beides durch, und die Verbindung steht sofort |

So sieht die erste Mauer aus – der Spinner dreht, während im Log weiter das Gerät auftaucht:

![Sony Zubehör-Suche](docs/images/sony-add-accessory.png)

## Schnellstart

### Voraussetzung: ADB am Fernseher aktivieren (der einzige Schritt, den du selbst machen musst)

Eine KI kann das nicht übernehmen, weil sie zu diesem Zeitpunkt noch nicht mit dem Fernseher verbunden ist.

**Sony BRAVIA (Android 9 / Google TV)**

1. `Einstellungen → System → Info`
2. Cursor auf **Version** stellen und OK **7-mal** drücken (manche neuen Firmwares wollen 10-mal auf **Android TV OS build**), bis „Du bist jetzt Entwickler“ erscheint
3. Zurück zu `Einstellungen → System → Entwickleroptionen`, **USB-Debugging** einschalten; falls vorhanden auch **Netzwerk-Debugging / ADB over network**
4. Fernseher eingeschaltet lassen, nicht in den Standby (manche Firmwares schalten das Debugging beim Aufwachen ab)

Danach die LAN-IP des Fernsehers notieren; der Rechner muss im selben Subnetz sein.

### Weg A: die KI machen lassen (empfohlen)

Dieses Repository ist gleichzeitig ein Agent-Skill: `SKILL.md` ist der Ablauf, den der Agent liest, `assets/` ist der Code, den er baut, `scripts/tv.sh` sind die Befehle, die er aufruft, und `references/pitfalls.md` sind die Sackgassen, die er nicht erneut betreten soll. Ins Skill-Verzeichnis klonen:

```bash
git clone https://github.com/koukanana/openappletvremot ~/.workbuddy/skills/openappletvremot
```

(Claude-Code-Nutzer: `~/.claude/skills/openappletvremot`. Andere Agenten müssen nur das Repository-Stammverzeichnis lesen können.)

Dann sagst du:

> Nutze den Skill openappletvremot, um die Xiaomi Bluetooth-Fernbedienung 2 Pro mit diesem Fernseher (192.168.x.x) zu verbinden, und repariere die Home- und TV-Taste. Skill und Code: https://github.com/koukanana/openappletvremot

Der Agent erledigt dann: ADB verbinden → beide Tools bauen und installieren → nach Gerätenamen-Schlüsselwort scannen und pairen → Sperre der versteckten API aufheben und HID hochziehen → Tastenbelegung schreiben → dich um Verifikation bitten. Du greifst nur an drei Stellen ein: „Zulassen“ am Fernseher antippen, Home + Menü 5 Sekunden gedrückt halten, am Ende die Tasten prüfen.

So verweist der Skill auf sich selbst (der Agent folgt dieser Kette):

```
Prompt „nutze den Skill openappletvremot“
   └── SKILL.md          Ablauf §1–§6, jeder Abschnitt zeigt auf Skript oder Quelldatei
        ├── scripts/tv.sh        connect / prep / pair / hid / keys / map / enable
        ├── assets/btpair/        Pairing + Umgehung der versteckten API + HID-Verbindung (Java)
        ├── assets/btremap/       Tastenbelegung über Bedienungshilfen (Java)
        ├── references/           Sony-Messwerte, 13 Sackgassen
        └── docs/                 Story- und Technik-Version (Chinesisch)
```

### Weg B: manuell

```bash
# 0. Abhängigkeiten: adb + JDK + Android SDK build-tools / platform-28
#    Fehlen sie, lädt build_apk.sh sie nach ~/.androidtv/sdk (~150 MB, einmalig)

# 1. Verbinden (der Fernseher zeigt einen Dialog, dort zulassen)
./scripts/tv.sh <TV_IP> connect
./scripts/tv.sh <TV_IP> info

# 2. Beide Tools bauen und installieren, Remap-Dienst aktivieren
./scripts/tv.sh <TV_IP> prep

# 3. Fernbedienung in den Pairing-Modus: Home + Menü 5 s halten, bis die LED schnell blinkt,
#    dann per Namens-Schlüsselwort scannen und pairen (keine MAC nötig, 8 Runden × 20 s)
./scripts/tv.sh <TV_IP> pair

# 4. HID prüfen: mInputDevices sollte : 2 zeigen, dumpsys input ein eventN listen
./scripts/tv.sh <TV_IP> hid

# 5. Tastenbelegung schreiben und aktivieren (Home → Startbildschirm, TV → Sony Eingangswahl)
./scripts/tv.sh <TV_IP> map '122>g>2;68>k>com.sony.dtv.tvx/com.sony.dtv.tvx.inputselect.InputSelectionActivity:178'
./scripts/tv.sh <TV_IP> enable
```

Nur die APKs neu bauen, ohne den Fernseher anzufassen:

```bash
./scripts/build_apk.sh assets/btpair  release/btpair.apk
./scripts/build_apk.sh assets/btremap release/btremap.apk
```

Format der Belegung: `Quell-Keycode>Aktion>Argument`, mehrere durch `;` getrennt. Aktionen: `g` = globale Aktion (1 zurück / 2 Home / 3 zuletzt / 4 Benachrichtigungen), `a` = Activity starten, `k` = Activity mit Key-Event starten, `s` = Assist-Intent.

Bei anderen TV-Marken ersetzt du die Activity der `k`-Aktion durch die Eingangswahl des jeweiligen Herstellers. Details in `references/sony-bravia.md`.

## Wie es funktioniert

Zwei APKs, zusammen unter 20 KB, beide ohne Root:

- **BTPair** (`assets/btpair`): ruft per Reflection `BluetoothAdapter.startDiscovery` und `createBond` auf, um zu pairen, danach `BluetoothHidHost.setPriority(100)` / `connect()`, um die Fernbedienung als Standard-Eingabegerät zu registrieren. Entscheidend ist die eine Zeile zur Ausnahme von den versteckten APIs – ohne sie wirft `connect()` immer `NoSuchMethodException`.
- **BTRemap** (`assets/btremap`): ein auf das Minimum reduzierter `AccessibilityService` mit `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`. Er kann nur zwei Dinge: eine globale Aktion ausführen (`GLOBAL_ACTION_HOME` usw.) oder eine Activity starten (optional mit `KEY_EVENT`-Extra). Die Belegung liegt in `Settings.Secure.btremap_map` und greift sofort, ohne Neustart.

Warum kein „Taste A abfangen und Taste B senden“: `INJECT_EVENTS` ist eine Signature-Permission, die Drittanbieter-Apps nicht bekommen. Die Obergrenze des Remappings ist also „globale Aktion + Activity-Start“ – und Home sowie TV passen genau hinein.

## Zur Sprachtaste (ungeprüft, nicht unmöglich)

Bekannte Fakten: Dieser Fernseher hat **kein eingebautes Mikrofon** – `/proc/asound/cards` ist leer, `audio_policy` listet keine Eingabegeräte, `pm list features` kennt nur `android.hardware.audio.output`. Sonys Sprachkette lautet „Mikrofon der Fernbedienung → Rückkanal über Bluetooth → Erkennung im Fernseher“. Wird die Sprachtaste also auf „Sprache starten“ gelegt, erscheint vielleicht die Oberfläche, aber es gibt auf der Fernseher-Seite nichts, was Ton aufnimmt.

Die Taste selbst funktioniert (sie meldet `KEYCODE_F5(135)`), und die Behandlung ist identisch zur TV-Taste: `135>s>` bildet sie auf `ACTION_ASSIST` ab. Was wirklich hängt, ist das Audio. Wer weitermachen will: PRs sind willkommen.

## Rückgängig machen

```bash
# Ursprüngliches Tastenverhalten sofort wiederherstellen
adb shell settings put secure enabled_accessibility_services ''

# Deinstallieren (Pairing bleibt, die Fernbedienung funktioniert weiter, nur die Tasten sind wieder original)
adb uninstall com.atvremote.remap
adb uninstall com.atvremote.pair
```

Zum Schluss am Fernseher: `Einstellungen → Entwickleroptionen → USB-Debugging-Autorisierungen widerrufen`. **ADB auf Port 5555 ist unverschlüsselt – danach wieder abschalten.**

## Aufbau

```
SKILL.md                    Einstiegspunkt des Agent-Skills (der Ablauf, den die KI liest)
scripts/tv.sh               Wrapper: connect/pair/keys/map/enable und weitere
scripts/build_apk.sh        Build ohne Gradle: aapt2 → javac → d8 → zipalign → apksigner
assets/btpair/              Pairing + HID-Aktivierung (Quellcode)
assets/btremap/             Tastenbelegung über Bedienungshilfen (Quellcode)
release/                    Fertige APKs (Debug-Signatur, für den Eigenbedarf)
references/sony-bravia.md   Sony-Menüpfade, Eingangswahl-Activity, Messbefehle
references/pitfalls.md      13 verifizierte Sackgassen, damit du sie nicht wiederholst
docs/让小米强兼索尼.md        Story-Version: warum und wie (Chinesisch)
docs/完整指南.md             Technik-Version: Schritt für Schritt + kompletter Prompt (Chinesisch)
```

## Kompatibilität

- Android 8.0+ (die Sperrliste versteckter APIs existiert seit API 28; auf 9.0 verifiziert), Android TV oder Google TV
- Fernbedienung: Xiaomi Bluetooth-Fernbedienung 2 Pro (verifiziert), Xiaomi Bluetooth-Fernbedienung 2 (gleiche Architektur, sollte gehen, ungetestet)
- Fernseher: Sony BRAVIA 2018–2020 (verifiziert). Andere Marken folgen demselben Prinzip, nur die Eingangswahl-Activity unterscheidet sich
- Rechner: macOS verifiziert. Unter Linux/Windows genügen eigenes adb und build-tools

## Andere TV-Marken unterstützen

Bisher läuft es nur auf Sony BRAVIA. Bei allen anderen Marken (TCL, Hisense, Skyworth, Xiaomi TV, Shield, Chromecast with Google TV …) ist das Prinzip identisch – der Unterschied sind nur **der Activity-Name der Eingangswahl** und **einige Keycodes**.

Wer einen anderen Fernseher hat: PRs sind sehr willkommen. Einfach testen, Modell und OS-Version nennen und unter `references/<marke>.md` die Messwerte ergänzen (Menüpfade, Eingangswahl-Activity, Keycode-Tabelle). Am Code muss dafür höchstwahrscheinlich nichts geändert werden.

## Haftungsausschluss

Du änderst Bluetooth-Pairing und Systemeinstellungen deines eigenen Fernsehers. Auf eigenes Risiko: Garantieansprüche können berührt werden, alte Pairings bleiben liegen, nach einem Systemupdate ist vielleicht alles erneut zu machen. Nicht auf geteilten oder produktiven Geräten ausprobieren. Der Code ist MIT – forke ihn, zerlege ihn, aber beschwere dich nicht bei mir.

## License

MIT
