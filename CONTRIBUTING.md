# Contributing

StayLoaded handles player identity and inventory data. Changes should be small, reviewable, and accompanied by tests that exercise failure paths.

Before opening a pull request:

```bash
./gradlew clean check releaseArtifacts
```

Do not copy source, assets, or documentation from Unplugged AFK or another fake-player project. Contributions must be original or clearly identify compatible third-party code and its license.

NMS changes belong only in a version adapter. Shared modules must not use reflection to hide version-specific internals.
