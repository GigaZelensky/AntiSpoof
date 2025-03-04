# AntiSpoof - Advanced Client Spoof Detection

<img src="https://i.imgur.com/7QkWF7E.png" alt="Banner" style="width:300px; height:auto;">

Advanced Minecraft plugin for detecting client spoofing attempts through brand analysis and channel monitoring. Designed for Spigot/Paper 1.20.4+ servers.

## Features

- **Client Brand Analysis**
  - Block/whitelist specific client brands (Fabric, Forge, etc.)
  - Validate brand formatting (special characters detection)
  - Geyser client spoof detection

- **Channel Monitoring**
  - Vanilla client verification
  - Channel whitelist/blacklist system
  - Strict mode for exact channel matching

- **Bedrock Player Integration**
  - Floodgate API support
  - Username prefix verification
  - Special handling modes (Ignore/Exempt)

- **Advanced Detection**
  - Multiple check modes (brand, channels, formatting)
  - Configurable punishment system
  - Real-time alerts with permission support

- **Diagnostic Tools**
  - In-game checking commands
  - Debug mode with detailed logs
  - Live player data inspection

## Installation

1. **Requirements**
   - Java 21+
   - Spigot/Paper 1.20.4+
   - [PacketEvents](https://github.com/retrooper/packetevents) 2.7.0

2. **Optional Dependencies**
   - Floodgate (Bedrock support)
   - PlaceholderAPI
   - ViaVersion/ViaBackwards

3. **Installation Steps**
   ```bash
   # Place the plugin in your plugins folder
   /plugins/AntiSpoof.jar
   
   # Restart server
   ```

## Configuration (`config.yml`)

```yaml
# Core Settings
delay-in-seconds: 3          # Check delay after join
block-non-vanilla-with-channels: false
check-brand-formatting: true # Validate brand characters
debug: false                 # Diagnostic logging

# Punishment System
punishments:
  - "kick %player% &amp;cSuspicious client detected!"
messages:
  alert: "&amp;8[&amp;c⚠&amp;8] &amp;e%player% flagged! &amp;7(%reason%)"

# Channel Configuration
blocked-channels:
  enabled: false
  exact-match: true
  whitelist-mode: FALSE      # FALSE/SIMPLE/STRICT
  values: [fabric-screen-handler-api-v1]

# Brand Management
blocked-brands:
  enabled: false
  exact-match: true
  whitelist-mode: false
  values: [fabric]

# Bedrock Handling
bedrock-handling:
  mode: EXEMPT               # IGNORE/EXEMPT
  punish-spoofing-geyser: true
  prefix-check:
    enabled: true
    prefix: "."
```

## Commands & Permissions

### Command Reference
| Command | Description | Permission |
|---------|-------------|------------|
| `/antispoof check [player]` | Check player status | `antispoof.command` |
| `/antispoof channels <player>` | View player channels | `antispoof.command` |
| `/antispoof brand <player>` | Show client brand | `antispoof.command` |
| `/antispoof reload` | Reload configuration | `antispoof.admin` |
| `/antispoof blockedchannels` | List channel rules | `antispoof.admin` |
| `/antispoof blockedbrands` | List brand rules | `antispoof.admin` |

### Permission Nodes
- `antispoof.command`: Base command access
- `antispoof.admin`: Configuration reload
- `antispoof.alerts`: Receive detection alerts
- `antispoof.bypass`: Bypass all checks

## FAQ

**Q: How does Bedrock player handling work?**  
A: Uses Floodgate API when available, falls back to username prefix checking.

**Q: What's considered a vanilla client?**  
A: Clients reporting "vanilla" brand with zero registered channels.

**Q: How to handle false positives?**  
1. Enable debug mode
2. Check player data with `/antispoof channels <player>`
3. Adjust blocked lists in config

**Q: Does this work with ViaVersion?**  
A: Yes, but requires ProtocolLib for best results with older versions.

## Support & Development

**Issue Tracking**  
[GitHub Issues](https://github.com/GigaZelensky/AntiSpoof/issues)

**Contributing**  
Pull requests welcome! Ensure compatibility with Java 21+ and follow existing code style.

## License

GNU GPLv3 © 2025 GigaZelensky

```
