# ReelKeep

Android app that shows up in the share menu. Share an Instagram reel or post to it, and it saves the photo or video at the biggest size Instagram has.

## Use it
1. No login needed for public posts. (Optional login on the home screen, only for private accounts.)
2. In Instagram, open a reel or post → **Share** → **Share to… / More** → **Save with ReelKeep**.
3. One item: it downloads straight away. Many items (carousel): tick the ones you want → **Download**.

Photos → `Pictures/ReelKeep`, videos → `Movies/ReelKeep`.

## Get the APK
Every push to `main` runs the GitHub Action in `.github/workflows/build.yml`. When it finishes, the APK is on the repo's **Releases** page as `ReelKeep.apk`.

To build on your PC instead: open the folder in Android Studio → Build → Build APK.

## Notes
- Uses Instagram's own website data, not an official API, so it may break if Instagram changes things.
- Save things for yourself; don't re-post other people's content.
