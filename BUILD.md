# Building the jar (Paper 1.21.11, Java 21)

Option A - on your PC (needs JDK 21 + Maven):
    mvn package
    -> target/DisplayMenu-1.0.0.jar  -> put it in your server's plugins/ folder

Option B - no install: push this folder to a GitHub repo, open Actions -> "build" -> run,
then download the "DisplayMenu" artifact (the jar).

Option C - IntelliJ IDEA: File > Open > pom.xml, then Maven panel > Lifecycle > package.
