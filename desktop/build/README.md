# Build resources

electron-builder reads app icons from this folder (`directories.buildResources: build`). Drop in, same basename `icon`:

- `icon.ico` for Windows (256x256, multi-size ico).
- `icon.icns` for macOS.
- `icon.png` for Linux (512x512).

Until these exist the build still succeeds with the default Electron icon (electron-builder only warns). Add a real Texera icon set before a signed release.
