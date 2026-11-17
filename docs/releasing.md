# Publishing a release

The way PODIUM's are published.

## The key

A PKCS12 keystore (RSA 4096, alias `orbitmusic`, valid 10,000 days) lives outside the repo, in
`%USERPROFILE%\.orbit-music-release\`, with its passwords in `keystore.properties` beside it. A copy of that
file sits in the project root, which git ignores. `app/build.gradle.kts` reads it, or the
`ORBIT_MUSIC_KEYSTORE_FILE`, `ORBIT_MUSIC_KEYSTORE_PASSWORD`, `ORBIT_MUSIC_KEY_ALIAS` and `ORBIT_MUSIC_KEY_PASSWORD`
environment variables. With neither, the release APK is built unsigned.

**Back the keystore up** (a password manager and an offline copy). Every release must be signed with
it: an APK signed with a different key can't install over an existing Orbit Music.

## Steps

1. Set `"version"` in `v.json` at the repo root (Gradle automatically computes `versionName` and `versionCode` as `major * 1,000,000 + minor * 1,000 + patch`, and the release workflow reads the tag from `v.json`).
2. Add the release to `CHANGELOG.md`, and write its notes in `docs/releases/v<version>.md`.
3. `./gradlew :app:assembleRelease`, then check it: `apksigner verify --print-certs` names `CN=Orbit Music`.
   Release builds aren't shrunk, so they run the same code as the debug build tested on the phone. To
   try one beside the phone's own without an uninstall, add `-Porbitmusic.applicationIdSuffix=.rc`.
4. Publish a GitHub Release tagged `v<version>`, with the APK attached and the notes from step 2
   followed by the APK's size and SHA-256. The README's download link names the file, so update it if
   the file's name changes. Never commit APKs.

## The website

Vercel serves `site/` from `main`: the project imports `tanmayy91/orbit-music` with its Root Directory at the repository root, and `vercel.json` selects the no-build **Other** framework with `site/` as the output directory. Don't host APKs there; the Download links stay GitHub URLs, and the certificate on the page (edition, size, SHA-256) is updated by hand with each release. The production URL is [orbit-music-player.vercel.app](https://orbit-music-player.vercel.app/).
