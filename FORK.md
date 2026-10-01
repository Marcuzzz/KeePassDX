# KeePassDX MO (fork)

Fork of [Kunzisoft/KeePassDX](https://github.com/Kunzisoft/KeePassDX) focused on editing the
same database from several devices through a cloud storage provider.

- Application ID `com.marcuzzz.keepassdx.libre`, installs next to the official app.
- Install page: https://marcuzzz.github.io/KeePassDX/ (latest APK from GitHub Releases, also usable with Obtainium).

## Safe cloud sync

Setting *App settings → Safe cloud sync* (enabled by default):

- **Load**: the file is copied from the provider to a local cache before being decrypted.
  Provider failures (e.g. Google Drive `StorageFileLoadException`) are retried with back-off
  and reported with a clear message instead of the raw provider error. A file that looks
  partially downloaded (HMAC / corrupted / signature error) is downloaded again.
- **Save**: the current remote file is read first. If it changed since it was loaded or last
  saved (SHA-256 of the content), it is **merged** into the open database before writing, so
  changes made on the laptop are not overwritten. If the merge is impossible (e.g. the
  master key was changed elsewhere), nothing is written and an error is shown.
- **Verify**: after writing, the file is read back and compared; on mismatch it is rewritten
  with explicit truncation.
- **Backups**: the last 3 distinct remote versions are kept in the app's private storage.
  If a database can't be loaded, the app offers to open the last backup read-only.
- The "database changed" dialog has *Merge* and *Reload* buttons.

## Recommended desktop setup (KeePass + Google Drive for desktop)

- In Google Drive for desktop, set the folder containing the database to **Available offline**
  (or use *Mirror files*), so KeePass never reads a placeholder or half-downloaded file.
- In KeePass, enable *Tools → Options → Advanced → "Use file transactions for writing
  databases"* and use **File → Synchronize** instead of a plain save when the phone may have
  changed the file (KeePass merges both versions, like the app does).
- Wait for Drive to finish syncing (tray icon) before editing on the other device.

## Release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. `git tag v<version>-mo<n> && git push origin v<version>-mo<n>`
3. The `Build APK` workflow builds `assembleLibreRelease`, signs it, attaches
   `KeePassDX-MO-release.apk` to a GitHub Release and deploys `deploy/index.html` to Pages.

Repository secrets: `SIGNING_KEYSTORE_B64` (base64 of the `.jks`), `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. A tag build fails if the keystore is missing.

Sync with upstream: `git fetch upstream && git merge upstream/master`.
