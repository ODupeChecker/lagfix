# lagfix

PaperMC / Spigot plugin (Java) that mitigates inventory packet spam exploits by rate-limiting `WINDOW_CLICK` (`ClickSlot`) packets via ProtocolLib.

## Features
- Listens to `PacketType.Play.Client.WINDOW_CLICK`.
- Tracks packets per player in a `HashMap<UUID, PacketCounter>`.
- Cancels packets when player exceeds configured packets/sec threshold.
- Optionally kicks player with `Too many inventory packets`.
- Resets counters every second with Bukkit scheduler.
- Limits log spam by only logging per-player violations at most once every 5 seconds.

## Configuration (`config.yml`)
```yaml
max-window-clicks-per-second: 20
kick-player: true
```

## Build
```bash
mvn clean package test
```

## Runtime requirements
- Paper/Spigot 1.20+
- ProtocolLib installed on server
