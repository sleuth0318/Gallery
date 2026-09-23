App build
---

1)Install Java latest
---
https://learn.microsoft.com/en-us/java/openjdk/download

new directory => ~Java/jdk-21 (extract here)
confirm java patrh => export JAVA_HOME=/home/fedora/Java/jdk-21
confirm jdk-21 => $JAVA_HOME/bin/javac -version

2) Install Android Sdk command line tools
---
https://developer.android.com/studio

new directory => ~Android/Sdk/cmdline-tools (extract here)
confirm path =>
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

Accept licenses => yes | sdkmanager --licenses  

Install required tools => sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" 

3) Download Gradle 
---
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install gradle 9.3.1

4) Build
----
gradle assembleFossDebug
gradle assembleFossRelease
