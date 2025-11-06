# Clean build for this shit
rm -rf /Users/jivie/.m2/repository/org/jetbrains/kotlin
./gradlew clean
./gradlew publishToMavenLocal
#cd ~/Projects
#(cd service-abstractions && ./gradlew clean && ./gradlew publishToMavenLocal)
#(cd kiteui && ./gradlew clean && ./gradlew publishToMavenLocal)
#(cd lightning-server && ./gradlew clean && ./gradlew publishToMavenLocal)
#(cd lightning-server-kiteui && ./gradlew clean && ./gradlew publishToMavenLocal)
#(cd lightning-time-tracker && ./gradlew clean && ./gradlew apps:viteRun)
#(cd service-abstractions && git pull && ./gradlew dependencies && ./gradlew publishToMavenLocal)
#(cd kiteui && git pull && ./gradlew dependencies && ./gradlew publishToMavenLocal)
#(cd lightning-server && git pull && ./gradlew dependencies && ./gradlew publishToMavenLocal)
#(cd lightning-server-kiteui &&git pull && ./gradlew dependencies && ./gradlew publishToMavenLocal)
#(cd lightning-time-tracker && ./gradlew dependencies && ./gradlew apps:viteRun)
