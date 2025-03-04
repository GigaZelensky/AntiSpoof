# AntiSpoof - Advanced Client Spoof Detection

<img src="https://i.imgur.com/Tyji4mJ.jpeg" alt="Banner" style="width:300px; height:auto;">

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
#        ___          __  _ _____                   ____
#       /   |  ____  / /_(_) ___/____  ____  ____  / __/
#      / /| | / __ \/ __/ /\__ \/ __ \/ __ \/ __ \/ /_  
#     / ___ |/ / / / /_/ /___/ / /_/ / /_/ / /_/ / __/  
#    /_/  |_/_/ /_/\__/_//____/ .___/\____/\____/_/     
#                            /_/                        
#                   Made by GigaZelensky

# ──────────────────────────────────────────────────────────
#                  AntiSpoof Configuration
# ──────────────────────────────────────────────────────────
# Welcome to AntiSpoof. This plugin helps server admins detect and manage 
# client-side modifications by analyzing client information sent by players.
#
# ⚠️ Note: While this plugin enhances security, it is not foolproof. 
# Skilled users can (ironically) spoof their client details to bypass detection.
#
# By default, the plugin only verifies whether a player claiming to use
# 'vanilla' has registered plugin channels, something that is impossible
# on a true vanilla client.
# ──────────────────────────────────────────────────────────

# ⏳ Delay (in seconds) before checking for client spoofing.
# Set to 0 for an immediate check upon player login. (Default: 0)
delay-in-seconds: 0

#  Super Strict Mode (Not Recommended)
# Blocks players who either:
#    - Do NOT have a "vanilla" client, OR
#    - Have registered plugin channels (indicating mods/plugins)
# ⚠️ This is an extremely strict mode and may block legitimate players, 
#    so enabling it is usually unnecessary and could negatively impact your player base.
block-non-vanilla-with-channels: false

#  Validate client brand formatting.
# If enabled, the plugin checks for unusual characters in the client brand.
check-brand-formatting: true

# ️ Debug Mode
# If enabled, logs client channels and brand details in the console when a player logs in.
debug: false

# ⚖️ Punishment Actions
# Define actions for detected spoofing attempts.
# Available placeholders: %player%, %reason%, %brand%
punishments:
  - "kick %player% &cSuspicious client detected!"
#  - "ban %player% &cClient spoofing detected"

#  Alert Messages
# Defines the message sent to players with the `antispoof.alerts` permission
# when a spoofing attempt is detected.
messages:
  alert: "&8[&cAntiSpoof&8] &e%player% flagged! &c%reason%"

# ──────────────────────────────────────────────────────────
#                 Channel Detection Settings
# ──────────────────────────────────────────────────────────
blocked-channels:
  #  Enable channel-based detection.
  enabled: false
  
  #  Exact Matching
  # If true, only exact matches will be blocked. If false, matches
  # that simply contain the specified value are allowed.
  exact-match: true
  
  # ⚪ Whitelist Mode
  # - FALSE: Block listed channels.
  # - SIMPLE: Only allow players with at least one whitelisted channel.
  # - STRICT: Only allow players who match the exact whitelist and have no extra channels.
  whitelist-mode: FALSE
  
  values:
    - "fabric-screen-handler-api-v1:open_screen"
    # Add more channels to block here
    # - "another:blocked:channel"
    # - "litematica"
    # - "fabric"

# ──────────────────────────────────────────────────────────
#                  Client Brand Detection
# ──────────────────────────────────────────────────────────
blocked-brands:
  #  Enable brand-based detection.
  enabled: false
  
  #  Exact Matching
  # If true, only exact matches will be blocked. If false, matches
  # that simply contain the specified value are allowed.
  exact-match: true
  
  # ⚪ Whitelist Mode
  # - FALSE: Block listed brands.
  # - TRUE: Only allow players using whitelisted brands.
  whitelist-mode: false
  
  values:
    - "fabric"
    # Add more brands to block here
    # - "vanilla"
    # - "optifine"

# ──────────────────────────────────────────────────────────
#                Bedrock Handling Settings
# ──────────────────────────────────────────────────────────
bedrock-handling:
  # Choose how to handle Bedrock players.
  # Options:
  #   - "IGNORE": Completely ignore these players; process them like regular players.
  #   - "EXEMPT": Process them but don't punish them for failing checks.
  #
  # Either way, Bedrock players won't get the anti-spoof treatment.
  # "EXEMPT" mode is highly recommended as "IGNORE" can easily falsely punish Bedrock players.
  mode: "EXEMPT"

  # Punish players claiming a client brand including a variation of "geyser" without being verified
  # as Bedrock players by the Floodgate API.
  # When enabled (true), these people will pay the price.
  punish-spoofing-geyser: true

  # Check Player Prefix:
  # This option checks whether a player is identified as a Bedrock player by verifying their prefix.
  # When enabled, the system will verify if the player's username or identifier starts with the specified prefix.
  # Default prefix is ".", and if a player's client brand includes "geyser" in any form without matching
  # the expected prefix, they will be punished.
  prefix-check:
    enabled: true
    # The prefix used to identify Bedrock players.
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
