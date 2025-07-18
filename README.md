# MultiSync-Stats
Minecraft PlaceholderAPI Multi-Server Synchronized Statistics Solution
# Note
Requires PlaceholderAPI
# Use
- Simply configure `MySQL` in your `config.yml` file, then connect all your servers to the same `MySQL` database. Set their `server-name` setting to match each server's name - we recommend keeping it consistent with the names configured in `BungeeCord`.
- In-game, use `/mss add <PlaceholderAPI>` to register the placeholders you want to sync, then retrieve your statistics with `%mss_<PlaceholderAPI>%`.
