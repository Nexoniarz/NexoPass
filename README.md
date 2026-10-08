# NexoPass

Passwords derived from a master, nothing stored in plain text. Android app, compatible with the `nexopass` script for NixOS.

*Made by **Nexoniarz***

## How it works

Every password is computed, never saved:

```
password = Argon2id(master, site + version + type + login)
```

The same master gives the same password on the phone and on the PC. What the app does store is the site list of each account (which sites, their current version, an archive of old versions), encrypted with AES-256-GCM under a key derived from that account's master.

- **Accounts.** Several accounts, each with its own master and site list. Switch between them in one tap.
- **App unlock.** A PIN, password or pattern (required), plus fingerprint/face if you want. It unlocks every account; the master is only typed to create or recover an account. The unlock data is encrypted twice, once with the Android Keystore (hardware), and too many wrong tries remove it.
- **Data breach?** Rotate the site to a new version; the old one goes to the archive.
- **Passwords** of 5-15 words or 12-64 characters, chosen per site.
- **Typos** like "discrod" get a "did you mean discord?".
- **Export/import**: OpenPGP files, the same format as the script, so the site list moves between phone and PC.
- No internet permission, screenshots blocked, clipboard cleared after a while, auto-lock, no cloud backup.

Android 8.0 or newer. Material 3, light and dark theme, animated background.

## Build

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Release builds are signed with the debug key so they install right away; use your own key if you publish the app.

## Compatibility

`Core.kt` must stay byte-for-byte compatible with the script: same Argon2id parameters, salts, word list (EFF Large Wordlist, checked by SHA-256), site-name rules and password layout. Changing any of them changes every password.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
