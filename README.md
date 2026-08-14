# RemoteBox Java

A Maven-based Java 17 Swing application inspired by **RemoteBox 3.7**. It provides a desktop client for managing VirtualBox guests with the `VBoxManage` command-line interface.

This is a reimplementation of https://remotebox.knobgoblin.org.uk/

Why?
Because I am using it under Windows and starting WSL just for that is tedious.
Also I am a Java developer, I like to iterate and improve it. Perl is not my thing.
All code written by AI.

Original is GPLv2, so I am keeping that.

## Included functionality

- Connect to a local VirtualBox installation or a remote host through a custom command prefix
- Persistent connection command/profile preference
- Guest list with state, OS type, and configured memory
- Detailed selected-guest information
- Create and remove guests
- Start guests headlessly
- Power off, ACPI shutdown, save state, discard saved state
- Pause, resume, and reset guests
- Take, restore, and delete snapshots
- Automatic guest-list refresh and an in-application operation log
- Background command execution so the Swing UI remains responsive

## Requirements

- Java 17 or later
- Maven 3.9 or later
- Oracle VirtualBox with `VBoxManage` available on `PATH`, or an explicit path/remote command configured in the connection dialog

## Build

From this directory:

```cmd
mvn clean package
```

Two JARs are created:

```text
target\remotebox-java-1.0.0.jar        library JAR (classes only)
target\remotebox-java-1.0.0-all.jar    runnable JAR (dependencies included)
```

## Run

Using Maven:

```cmd
mvn exec:java
```

Using the built JAR:

```cmd
java -jar target\remotebox-java-1.0.0-all.jar
```

## Use as a library

The library JAR can be embedded into another Swing application. It needs
FlatLaf and JNA Platform on the classpath (see the dependencies in `pom.xml`).

```java
com.remoteboxjava.RemoteBox.showWindow();
```

`RemoteBox.showWindow()` may be called from any thread, opens the window on
the event dispatch thread and brings an already open window to the front. It
does not install a look and feel, so the host application's theme is kept, and
the application never calls `System.exit` — closing the window only disposes
it.

## Connecting to VirtualBox

The default local command on Windows is:

```text
VBoxManage.exe
```

If VirtualBox is not on `PATH`, configure a full executable path in **Connection → Connect**:

```text
"C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"
```

For a remote machine reachable by SSH, configure a command prefix such as:

```text
ssh virtualbox-user@virtualbox-server VBoxManage
```

The SSH account must be able to run `VBoxManage` non-interactively. Use SSH keys or an existing authenticated SSH agent rather than embedding passwords in a command.

## Connection profiles

**File → Connection Profiles** manages any number of saved connections. Each
profile stores a name, a transport, an address and — for the web service — a
user name:

- **RemoteBox Web Service** — a server URL such as `http://vbox.example.test:18083`
- **Local / SSH VBoxManage** — a command such as `ssh user@host VBoxManage`

Switching the transport swaps the address row to the one belonging to that
transport, so both addresses are kept and switching back and forth loses
nothing.

Exactly one profile can be marked **Connect to this profile when RemoteBox
starts**. **File → Connect** offers the saved profiles in a drop-down; picking
one fills in the connection details, and only the password still has to be
typed. Passwords are never written to the settings file.

## Settings

Settings are stored as JSON in the platform's configuration location:

| Platform | Location |
| --- | --- |
| Windows | `%APPDATA%\RemoteBoxJava\settings.json` |
| macOS | `~/Library/Application Support/RemoteBoxJava/settings.json` |
| Linux / other | `$XDG_CONFIG_HOME/remotebox-java/settings.json` (default `~/.config/remotebox-java/…`) |

```json
{
  "confirm.actions": true,
  "display.rdpClient": "mstsc.exe /w:%X /h:%Y /v:%h:%p",
  "profiles.0.name": "Laptop",
  "profiles.0.transport": "web-service",
  "profiles.0.endpoint": "http://192.168.178.168:18083",
  "profiles.0.username": "vjay",
  "profiles.autoConnect": "Laptop",
  "profiles.count": 1,
  "refresh.seconds": 30
}
```

On first start the file is created and seeded, in this order, from:

1. settings of a previous version of this application, then
2. an existing RemoteBox installation.

RemoteBox is searched in `$XDG_CONFIG_HOME`, `~/.config`, `~/.remotebox` and,
on macOS, `~/Library/Application Support/remotebox`. On Windows every installed
WSL distribution is searched as well, since RemoteBox is a Perl/GTK application
that usually runs there. Imported values are the connection profile name, server
URL, user name, and the RDP/VNC client command templates. When settings are
adopted from RemoteBox the application reports this in a dialog on that first
start; RemoteBox's own configuration is never modified. The imported connection
becomes the first profile and is selected as the startup profile.

The current settings path is shown in the message log at startup and in
**File → Preferences**.

## Notes

RemoteBox 3.7 is a Perl/GTK client for the VirtualBox web service. This Java version follows its practical VM-management workflow but uses `VBoxManage` as a dependency-free transport layer, making it suitable for local instances and remote environments that expose `VBoxManage` through SSH.
