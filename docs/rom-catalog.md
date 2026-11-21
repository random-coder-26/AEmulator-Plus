# ROM catalog

Open **ROM catalog** using the list icon on the library screen, its row in the
add menu, or its row in app settings. The catalog is a list of ROM download
links maintained on a server; no ROM is bundled in the app.

The catalog source is **fixed in the app**. It cannot be changed, and its
address is not shown anywhere in the UI. (Earlier versions had a pencil button
to change the URL; it was removed, and a custom address saved by an older
version is ignored.) The toolbar has two buttons: **Submit a ROM** (plus icon)
and **Refresh**.

## Browsing

Sections appear as a compact index, one per variant, in the order the server
sends them. Tap a section to open it, and use the toolbar back arrow or system
Back to go one level up. Each section shows how many ROMs it has (sub category
folders included); an empty section shows **No ROMs at the moment.**

Inside a section, each version category is its own folder (a sub category), in
the order the server sends them, and works like a section: tap it to see only
its ROMs, Back returns to the section. A ROM without a version category is
listed directly in the section, below the folders.

Each ROM card shows:

- the ROM name and the device;
- the ROM version (the version category is the folder the card is in);
- the file size and the emulator version it was tested on (when given);
- notes (when given);
- who submitted it.

A ROM with a download link has a download icon. A ROM without a usable link
shows **ROM download URL isn't available right now.** Entries that are malformed
(no name or device, or a link that is not a plain `http(s)` link) are reported
by entry number in a warning line without hiding the valid ones.

## Downloading

The download icon first checks the link:

- If it answers with a file (zip, rar, 7z, tar, gz, img, `.aessvm` and similar
  archive or image types, or an attachment), the file is downloaded into the
  app's `downloads` folder and handed straight to the importer on the home
  screen. Files of 16 MiB or more use up to 6 parallel connections in 8 MiB
  chunks when the server supports byte ranges. The dialog shows the file name,
  size and speed, and **Cancel** stops the download. Leftover downloads older
  than 24 hours are removed.
- If it answers with a web page (HTML, text, JSON, XML), an HTTP error, or the
  check cannot be completed, the link opens in the default browser instead. If
  no default browser is set, Android offers a browser selection.

Downloads run in a foreground service with a notification (progress and a
**Cancel** action) and hold a wake lock, so they keep going when the screen
turns off or the app is minimised. **Hide** in the download dialog closes the
dialog and leaves the download running. Only one download runs at a time. If the
catalog screen is not open when it ends, a notification says so: tapping
**ROM downloaded** hands the file to the importer, and a link that turned out to
be a web page offers to open it in the browser. Android 13 and newer need the
notification permission to show these notifications; the download itself does
not depend on it. A download is not resumed if the app process is killed or the
connection drops for good.

Links containing a user name or password, and non-web schemes, are never opened.
HTTPS redirects cannot silently downgrade to HTTP.

## Submitting a ROM

The plus button opens a form to share a ROM download link. Submissions are
reviewed before they appear in the catalog. The categories (variants and
version categories) come from the server.

| Field | Required | Limit |
| --- | --- | --- |
| Variant, Version category | yes | chosen from the server list |
| ROM name | yes | 120 characters |
| ROM version | yes | 60 characters |
| Device | yes | 120 characters |
| Download URL | yes | 2048 characters, `http(s)` only |
| Your name | yes | 60 characters |
| File size in MB | no | a number, 0 or more |
| Emulator version | no | 40 characters |
| Notes | no | 1000 characters |

The server can answer that the link was already submitted, that too many
submissions were made (the app shows roughly how many minutes to wait), or that
the submission was rejected with a reason. A thank-you message is shown when it
is accepted.

## Server API

The app talks to one server, with these endpoints:

| Endpoint | Used for |
| --- | --- |
| `GET /api/roms` | the catalog: `{"roms":[{variant, version_category, name, rom_version, device, submitted_by, download_url, file_size_bytes, emulator_version, notes}]}` |
| `GET /api/categories` | the variants and version categories for the submit form |
| `POST /api/submit` | a new submission (HTTP 201 when accepted, 400 / 409 / 429 on errors) |

Limits on the app side: a catalog response is bounded to 1 MiB and 10000 ROMs,
with at most five redirects, 15 second connection and read timeouts, and an
HTML response is rejected as not a catalog. A failed refresh leaves the last
successfully displayed list visible with an error and a **Retry** button. There
is no persistent offline cache.

The parser also still understands the older plain-text `rom.list` format
(status `-1` unknown, `0` does not boot, `1` boots with issues, `2` works), but
since the source is fixed to the server above it is only used if the server
itself ever returns that format.

For developers: the source address is the constant `CatalogUrls.DEFAULT` in
`app/src/main/java/app/aemu/catalog/RomCatalog.kt`. Changing where the catalog
comes from is a code change, not a setting.

## Experimental unlock

Tap the **AEmulator Sunset** title 42 times on the library screen or app-settings
about card. Both titles share a counter that survives rotation and app restarts.
At tap 42, the app saves the experimental-feature unlock, shows a translated
toast, and opens `https://www.youtube.com/watch?v=LVHBcsiZcyI` in the browser.
Further taps do not reopen the video. Unlocking does not turn experimental
features on by itself.

Currently this reveals the **Host camera (experimental)** setting. A ROM with
the camera already enabled keeps its setting available; updating does not
silently disable existing camera configurations. The catalog, setup-skip
option, vibration, navigation controls and ordinary ROM support are not gated.
