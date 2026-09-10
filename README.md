# Server Scanner

## Overview
Server Scanner is a tool for scanning/finding and managing Minecraft servers. It allows users to scan a network for open Minecraft servers, scan specific IP addresses for open ports, and manage the server list through an intuitive user interface.

## Features
- **Server Scanning**: Scan a network for open Minecraft servers.
- **Port Scanning**: Scan specific IP addresses for open ports.
- **Delete All**: Deletes all servers from the server list.
- **Delete via Regex**: Deletes servers from the server list that match a given regex pattern.

## Requirements

- Minecraft Java Edition **26.2** (other 26.x releases are not covered by this build).
- Fabric Loader **0.19.5 or newer** and Fabric API for **26.2**.
- **Java 25** to build and run the mod. Set JAVA_HOME to a JDK 25 installation.
- Install the mod on the client.

## Build from Source
1. Clone the repository:
    ```sh
    git clone https://github.com/weristmaxi/Minecraft-Server-Scanner.git
    ```
2. Navigate to the project directory:
    ```sh
    cd Minecraft-Server-Scanner
    ```
3. Build the project using Gradle:
    ```sh
    ./gradlew build
    ```
On Windows, use `.\gradlew.bat build`. The Gradle wrapper downloads Gradle 9.5.1 and verifies its SHA-256 checksum.

The installable JAR is `build/libs/server-scanner-1.2.0+26.2.jar`; the `-sources.jar` is for development.

`./gradlew build` also runs compatibility tests through Fabric Loader, applying the multiplayer mixins against Minecraft 26.2. For an interactive check, run `./gradlew runClient` and verify the scanner buttons, text fields, scrolling, resizing, adding results, and deletion controls in the multiplayer menu. Use a disposable server list for deletion checks.

---

Thank you for using Server Scanner!