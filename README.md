# AntiSpoof Plugin

Advanced client spoof detection with automatic punishments.

## Features
- Detect fake vanilla clients
- Channel-based detection
- Brand formatting checks
- Customizable punishments
- Bypass permission support

## Commands/Permissions
- `antispoof.alerts` - Receive detection alerts
- `antispoof.bypass` - Bypass all checks

## Configuration
Edit `config.yml`:
```yaml
delay-in-seconds: 3 # Check delay after join
block-non-vanilla-with-channels: true # Block any non-vanilla with channels
check-brand-formatting: true # Block brands with colors/weird chars
punishments: # Commands to run on detection
  - "kick %player% Reason"
  - "ban %player% Cheating"