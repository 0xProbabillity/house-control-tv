#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
JDK="${HOUSE_TV_JDK:-/opt/homebrew/opt/openjdk@17}"
mkdir -p .local/test-libs .local/test-classes
if [ ! -f .local/test-libs/json.jar ]; then
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o .local/test-libs/json.jar
fi
CP=".local/test-libs/json.jar:$SDK/platforms/android-34/android.jar"
SRC=android/src/dev/housecontrol/tv
"$JDK/bin/javac" -cp "$CP" -d .local/test-classes $SRC/{SharingTransport,SecretStore,SecureVault,SmartLifeClient,CloudMapper,DemoHome,LocalMapper,MiniWebSocket,HomeAssistantClient,HueClient}.java tests/java/StandaloneTests.java tests/java/LocalHubTests.java
"$JDK/bin/java" -cp ".local/test-classes:$CP" dev.housecontrol.tv.StandaloneTests

# Home Assistant and Hue against the mock hubs on loopback (a current and a pre-2022.4 Home Assistant).
RUN=$(mktemp -d)
python3 tools/mock_hubs.py --ha-port 0 --hue-port 0 --state-file "$RUN/hubs.json" < /dev/null > "$RUN/hubs.log" 2>&1 & MOCK=$!; disown
python3 tools/mock_hubs.py --ha-port 0 --hue-port 0 --legacy --state-file "$RUN/legacy.json" < /dev/null > "$RUN/legacy.log" 2>&1 & LEGACY=$!; disown
trap 'kill $MOCK $LEGACY 2>/dev/null; rm -rf "$RUN"' EXIT
for _ in $(seq 50); do [ -s "$RUN/hubs.json" ] && [ -s "$RUN/legacy.json" ] && break; sleep .1; done
"$JDK/bin/java" -cp ".local/test-classes:$CP" dev.housecontrol.tv.LocalHubTests "$RUN/hubs.json" "$RUN/legacy.json"
