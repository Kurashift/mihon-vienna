# Repository working conventions

- Work on the Git `main` branch. The former `main-backup` branch is deprecated and deleted.
- The default Android build and install target is `vienna` (`:app:assembleVienna`).
- The `vienna` build keeps application ID `app.mihon.dev` for in-place upgrades.
- Do not build or install `localFirst`, `release`, or other variants unless the user explicitly asks.
- For the connected primary device, prefer the `arm64-v8a` APK.
