# AntiSpoof - Advanced Client Spoof Detection

<img src="https://i.imgur.com/Tyji4mJ.jpeg" alt="Banner" style="width:300px; height:auto;">

Advanced, heavily customizable Minecraft plugin for detecting client spoofing attempts through brand analysis and channel monitoring. Designed for Spigot/Paper 1.20.4+ servers.

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

- **Discord Webhooks**
  - Fully customizable Discord Webhooks
  - Sends alerts when a player flags

## Installation

1. **Requirements**
   - Java 21+
   - Spigot/Paper 1.20.4+

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

# ️ Debug Mode
# If enabled, logs client channels and brand details in the console when a player logs in.
debug: false

# ──────────────────────────────────────────────────────────
#                  Core Detection Settings
# ──────────────────────────────────────────────────────────

# Vanilla Spoof Detection
# Detects when a player claims to use vanilla but has registered plugin channels
vanillaspoof-check:
  # Whether to check if a player claiming "vanilla" client has plugin channels
  enabled: true
  # Custom alert messages for this specific violation
  alert-message: "&8[&cAntiSpoof&8] &e%player% flagged! &cVanilla client with plugin channels"
  console-alert-message: "%player% flagged! Vanilla client with plugin channels"
  # Whether to punish the player if detection is positive
  punish: false
  # Punishment actions to execute
  # Available placeholders: %player%, %reason%, %brand%, %channel%
  punishments:
    - "kick %player% &cVanilla spoof detected: %reason%"
    # - "ban %player% &cVanilla client spoofing detected"

# Super Strict Mode (Not Recommended)
# Blocks players who either:
#    - Do NOT have a "vanilla" client, OR
#    - Have registered plugin channels (indicating mods/plugins)
# ⚠️ This is an extremely strict mode and may block legitimate players
non-vanilla-check:
  # Whether to enable this strict check
  enabled: false
  # Custom alert messages for this specific violation
  alert-message: "&8[&cAntiSpoof&8] &e%player% flagged! &cClient modifications detected"
  console-alert-message: "%player% flagged! Client modifications detected"
  # Whether to punish the player if detection is positive
  punish: false
  # Punishment actions to execute
  # Available placeholders: %player%, %reason%, %brand%, %channel%
  punishments:
    - "kick %player% &cNon-vanilla client with channels detected"
    # - "tempban %player% 1h &cUsing a modified client"

# ──────────────────────────────────────────────────────────
#                 Channel Detection Settings
# ──────────────────────────────────────────────────────────
blocked-channels:
  # Enable channel-based detection
  enabled: false
  
  # ⚪ Whitelist Mode
  # - FALSE: Block listed channels.
  # - SIMPLE: Only allow players with at least one whitelisted channel.
  # - STRICT: Only allow players who match the exact whitelist and have no extra channels.
  whitelist-mode: FALSE
  
  # List of channels to block/whitelist
  values:
    - "^fabric-screen-handler-api-v1:open_screen$"
    # Add more channels to block here
    # - "another:blocked:channel"
    # - "(?i).*litematica.*"
    # - "(?i).*fabric.*"

  # Custom alert messages for this specific violation
  alert-message: "&8[&cAntiSpoof&8] &e%player% flagged! &cUsing blocked channel: &f%channel%"
  console-alert-message: "%player% flagged! Using blocked channel: %channel%"

  # Alert when a player's plugin channel is modified
  modifiedchannels:
    enabled: true
    alert-message: "&8[&cAntiSpoof&8] &e%player% modified channel: &f%channel%"
    console-alert-message: "%player% modified channel: %channel%"
    discord-alert: false

  # Whether to punish the player if detection is positive
  punish: false
  # Punishment actions to execute
  # Available placeholders: %player%, %reason%, %brand%, %channel%
  punishments:
    - "kick %player% &cBlocked channel detected: %channel%"
    # - "tempban %player% 1d &cUsing blocked mod channels"

# ──────────────────────────────────────────────────────────
#                  Client Brand Detection
# ──────────────────────────────────────────────────────────
blocked-brands:
  # Enable brand-based detection
  enabled: true
  
  # ⚪ Whitelist Mode
  # - FALSE: Block listed brands.
  # - TRUE: Only allow players using whitelisted brands.
  whitelist-mode: true
  
  # List of brands to block/whitelist
  values:
    - "^vanilla$"
    - "^fabric$"
    - "^lunarclient:v\\d+\\.\\d+\\.\\d+-\\d{4}$"
    - "^Feather Fabric$"
    - "^labymod$"
    # Add more brands to block here
    # - "^optifine$"

  # Alert messages for non-whitelisted brands
  alert-message: "&8[&eAntiSpoof&8] &7%player% joined using client brand: &f%brand%"
  console-alert-message: "%player% joined using client brand: %brand%"

  # Choose whether a player not matching the brand list should count as a flag.
  count-as-flag: true
  
  # Whether to punish the player if detection is positive
  punish: false
  # Punishment actions to execute
  # Available placeholders: %player%, %reason%, %brand%
  punishments:
    - "kick %player% &cBlocked client brand detected: %brand%"
    # - "tempban %player% 12h &cUsing blocked client"

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

  # Geyser Spoofing Detection
  # Detects players claiming a client brand including a variation of "geyser" without being verified
  # as Bedrock players by the Floodgate API.
  geyser-spoof:
    # Whether to enable geyser spoofing detection
    enabled: true
    # Custom alert messages for this specific violation
    alert-message: "&8[&cAntiSpoof&8] &e%player% flagged! &cGeyser client spoofing"
    console-alert-message: "%player% flagged! Geyser client spoofing with brand: %brand%"

    # Whether to punish the player if detection is positive
    punish: false
    # Punishment actions to execute
    # Available placeholders: %player%, %reason%, %brand%
    punishments:
      - "kick %player% &cGeyser client spoofing detected"
      # - "ban %player% &cGeyser client spoofing"

  # Check Player Prefix:
  # This option checks whether a player is identified as a Bedrock player by verifying their prefix.
  # When enabled, the system will verify if the player's username or identifier starts with the specified prefix.
  # Default prefix is ".", and if a player's client brand includes "geyser" in any form without matching
  # the expected prefix, they will be flagged.
  prefix-check:
    enabled: true
    # The prefix used to identify Bedrock players.
    prefix: "."

# ──────────────────────────────────────────────────────────
#                 Discord Webhook Settings
# ──────────────────────────────────────────────────────────
discord:
  enabled: false
  webhook: ""
  embed-title: "**AntiSpoof Alert**"
  embed-color: "#2AB7CA"
  violation-content:
    - "**Player**: %player%"
    - "**Reason**: %console_alert%" # Gets the console alert of the specific flagged check
    - "**Client Version**: %viaversion_version%" # Requires Viaversion and PlaceholderAPI
    - "**Brand**: %brand%"
    - "**Channels**:"
    - "%channel%" # Vertical channel list

# ──────────────────────────────────────────────────────────
#                Legacy Punishment Settings
# ──────────────────────────────────────────────────────────
# Legacy global punishment system (for backward compatibility)
# These punishments will be used if a specific check has 'punish: true'
# but doesn't have its own punishments defined
# Available placeholders: %player%, %reason%, %brand%, %channel%
punishments:
  - "kick %player% &cSuspicious client detected!"
  # - "ban %player% &cClient spoofing detected"

# ──────────────────────────────────────────────────────────
#                 Legacy Alert Messages
# ──────────────────────────────────────────────────────────
# Legacy global alert system (for backward compatibility)
# These messages will be used if a specific check is enabled
# but doesn't have its own alerts defined
# Available placeholders: %player%, %reason%, %brand%, %channel%
# Defines the message sent to players with the `antispoof.alerts` permission
# when a spoofing attempt is detected.
messages:
  # Message shown to players with antispoof.alerts permission
  alert: "&8[&cAntiSpoof&8] &e%player% flagged! &c%reason%"
  # Message logged to console (no color codes needed)
  console-alert: "%player% flagged! %reason%"
  # Message for multiple violations
  multiple-flags: "&8[&cAntiSpoof&8] &e%player% has multiple violations: &c%reasons%"
  # Message for console when multiple violations
  console-multiple-flags: "%player% has multiple violations: %reasons%"
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
