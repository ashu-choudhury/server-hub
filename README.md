# Server Hub

[![Build status](https://github.com/ashu-choudhury/server-hub/workflows/Build/badge.svg)](https://github.com/ashu-choudhury/server-hub/actions)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-green.svg)](https://developer.android.com)
[![Package](https://img.shields.io/badge/Package-com.termux-orange.svg)](https://github.com/ashu-choudhury/server-hub)

**Server Hub** transforms your Android phone into an ultra-efficient, dedicated, always-on Linux server and game server host. Engineered from the ground up with a streamlined Super AMOLED dark UI, automated first-launch onboarding, built-in OpenSSH server, userspace WireGuard tunneling, and accessible port routing, Server Hub delivers a clean server appliance experience without root.

---

## 🚀 Key Features

- **⚡ Headless Linux Environment**: Full APT/PKG package ecosystem powered by the proven Termux userland core. Install Python, Node.js, Rust, Go, OpenJDK, Git, C/C++ toolchains, and databases seamlessly.
- **🔒 Automated OpenSSH Server**: Automatic host key provisioning, secure password setup, and one-tap SSH connection string copying (`ssh u0_a...@[ip] -p 8022`).
- **🌐 Native Userspace WireGuard Engine**: Integrated `wireguard-go` userspace tunnel with custom `AndroidStdBind` socket routing, bypassing SELinux netlink restrictions on unrooted Android devices.
- **🎮 Game Server & Port Routing**: Configure, toggle, and manage live port rules with presets for:
  - **Minecraft** (Java `25565`, Bedrock `19132`)
  - **NVGT Audio Game Servers**
  - **Palworld, Valheim & Terraria**
  - **Web & API Frameworks** (Node.js, Express, FastAPI, Flask, Django)
- **💾 1-Click Sandbox Backup & Restore**: Export full environment snapshots (installed packages, configuration files, SSH keys, dotfiles) to compressed `.tar.gz` archives in your `Downloads` directory, with automated permission recovery on restore.
- **🌙 Battery-Optimized AMOLED UI**: Pure `#000000` dark theme designed to minimize screen power consumption, OLED burn-in, and thermal overhead during 24/7 server uptime.
- **♿ 100% Screen Reader Accessible**: Thorough TalkBack and screen reader optimizations with custom accessibility labels, explicit focus order, and distinct auditory/tactile state toggles.

---

## 🛠️ Getting Started

### 1. Download & Install
Download the latest APK release (`server-hub_*.apk`) from the [Releases](https://github.com/ashu-choudhury/server-hub/releases) tab.

### 2. First-Launch Setup Wizard
On initial launch, Server Hub's multi-step Setup Wizard guides you through:
1. **Sandbox Initialization**: Extracts the isolated Linux userland into private app storage (`/data/data/com.termux/`).
2. **SSH Service Configuration**: Installs `openssh` and `termux-auth`, configures your server user password, and starts `sshd` on port `8022`.
3. **WireGuard Tunneling (Optional)**: Import or configure your WireGuard client interface (`[Interface]` and `[Peer]`), verify handshake status, and establish a private connection.
4. **Dashboard Access**: Launches the main Server Hub controller.

### 3. Connecting to Your Server
Connect from any computer, tablet, or terminal client on your local network or WireGuard mesh:
```bash
ssh <username>@<server-ip> -p 8022
```
Your assigned username (e.g. `u0_a123`) and active IP addresses are displayed directly on the Server Hub dashboard with a single-tap **Copy Connection Command** button.

---

## 🎮 Hosting Game Servers

You can run your favorite game servers directly within the Server Hub sandbox:

### Example: Running a PaperMC / Purpur Minecraft Server
```bash
# 1. SSH into Server Hub
ssh <username>@<server-ip> -p 8022

# 2. Install OpenJDK 21 & Wget
pkg update && pkg install -y openjdk-21 wget

# 3. Create server directory and download jar
mkdir -p ~/minecraft && cd ~/minecraft
wget https://api.papermc.io/v2/projects/paper/versions/1.20.4/builds/497/downloads/paper-1.20.4-497.jar -O server.jar

# 4. Accept EULA and run server
echo "eula=true" > eula.txt
java -Xms1G -Xmx2G -jar server.jar --nogui
```
In the **Server Hub** app, add a port routing rule for port `25565` (TCP/UDP) to expose it to your players over your WireGuard VPN or LAN.

---

## 📦 Architecture & Directory Hierarchy

| Path | Purpose |
| :--- | :--- |
| `/data/data/com.termux/files/usr` | Linux `$PREFIX` (binaries, libraries, headers) |
| `/data/data/com.termux/files/usr/bin` | Executables (`bash`, `sshd`, `apt`, `pkg`, `node`, `python`) |
| `/data/data/com.termux/files/home` | User `$HOME` directory (projects, configurations, `.ssh/`) |
| `/data/data/com.termux/files/usr/etc/ssh` | OpenSSH daemon configuration & host keys |

---

## 🔨 Building from Source

### Prerequisites
- JDK 17
- Android SDK (API 34, Build-Tools 34.0.0, NDK r26+)
- Git

### Build Command
Clone the repository and build the debug APK using the Gradle wrapper:

```bash
git clone https://github.com/ashu-choudhury/server-hub.git
cd server-hub

# Assemble debug APKs
./gradlew assembleDebug
```

Generated APKs will be located at:
```
app/build/outputs/apk/debug/server-hub_*.apk
```

---

## 🤝 Acknowledgments & Credits

Server Hub builds upon the foundational Linux userland, terminal emulation, and bootstrap toolchains developed by the amazing [Termux](https://github.com/termux) project and its open-source contributors. We express deep gratitude to the Termux team for making userland Linux on Android possible.

---

## 📄 License

Server Hub is open-source software licensed under the [GPL-3.0 License](LICENSE.md).
