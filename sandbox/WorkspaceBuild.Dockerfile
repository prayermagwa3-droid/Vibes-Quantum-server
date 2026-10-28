# Build in trusted CI, scan the image, and pin its immutable digest in production.
# Gradle 8.13 is selected to match Android Gradle Plugin 8.13.x.
FROM gradle:8.13-jdk17

USER root
ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/gradle/.gradle \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
ENV PATH="${PATH}:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl unzip zip git python3 python3-pytest nodejs npm \
       maven golang-go rustc cargo build-essential \
    && rm -rf /var/lib/apt/lists/*

ARG ANDROID_CMDLINE_TOOLS_REVISION=15859902
ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /tmp/android-cli \
    && curl --fail --location --retry 3 \
       "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_REVISION}_latest.zip" \
       --output /tmp/android-cli.zip \
    && echo "${ANDROID_CMDLINE_TOOLS_SHA256}  /tmp/android-cli.zip" | sha256sum --check - \
    && unzip -q /tmp/android-cli.zip -d /tmp/android-cli \
    && mv /tmp/android-cli/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-cli /tmp/android-cli.zip \
    && yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_HOME}" \
       "platform-tools" "platforms;android-35" "platforms;android-36" \
       "build-tools;35.0.0" "build-tools;36.0.0" \
    && chmod -R a+rX "${ANDROID_HOME}"

# Warm the exact Android Gradle Plugin/Kotlin/JUnit/SDK dependency graph while image build
# networking is enabled. The final job container still runs with --network=none.
COPY sandbox/fixtures/disposable-android /opt/vibes/fixtures/disposable-android
RUN gradle --no-daemon --stacktrace -p /opt/vibes/fixtures/disposable-android \
      :app:testDebugUnitTest :app:assembleDebug \
    && mkdir -p /opt/vibes/cache/gradle /opt/vibes/cache/npm /opt/vibes/cache/maven-repository \
                /opt/vibes/cache/cargo /opt/vibes/cache/go-mod \
    && cp -a "${GRADLE_USER_HOME}/." /opt/vibes/cache/gradle/ \
    && chmod -R a+rX /opt/vibes/cache /opt/vibes/fixtures \
    && test -s /opt/vibes/fixtures/disposable-android/app/build/outputs/apk/debug/app-debug.apk \
    && test -s /opt/vibes/fixtures/disposable-android/app/build/test-results/testDebugUnitTest/TEST-com.vibes.fixture.MainActivityTest.xml

WORKDIR /workspace
USER 65534:65534
