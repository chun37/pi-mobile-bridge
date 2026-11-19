# Third-party notices

The MIT license in `LICENSE` applies to the original Pi Mobile Bridge code and documentation. It does not relicense third-party components.

## Gradle Wrapper

`android/gradlew` and `android/gradle/wrapper/gradle-wrapper.jar` are Gradle Wrapper components, distributed under the Apache License, Version 2.0.

- Project: https://github.com/gradle/gradle
- License: https://www.apache.org/licenses/LICENSE-2.0
- Copyright: the Gradle authors; see the upstream source and notices.

## External dependencies

Dependencies are downloaded by npm, Go modules, Gradle, and the Android SDK tools. Their code is not vendored into this repository, except for the Gradle Wrapper noted above.
Their respective licenses and notices continue to apply.
Dependency versions are recorded in `package-lock.json`, `relay/go.mod`, `relay/go.sum`, and the Android Gradle build files.

Pi, herdr, Android, and Tailscale are separate projects. This repository does not claim ownership of those projects or their trademarks.
