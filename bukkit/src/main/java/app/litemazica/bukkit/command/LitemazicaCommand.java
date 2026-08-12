package app.litemazica.bukkit.command;

import app.litemazica.bukkit.LitemazicaPlugin;
import app.litemazica.bukkit.platform.BukkitAudience;
import app.litemazica.core.maze.MazeName;
import app.litemazica.core.maze.PlacedMaze;
import app.litemazica.core.maze.RegenInterval;
import app.litemazica.core.util.ParseUtils;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles {@code /litemazica <editor|start|generate|place|files|list|remove|regen|reload>}. Each subcommand
 * is permission-gated; the actual work (fetch, placement, persistence, and
 * scheduled regeneration) lives in {@link app.litemazica.core.maze.MazeService}.
 */
public final class LitemazicaCommand implements CommandExecutor, TabCompleter
{
    // Permission-gated subcommands. "help" is handled separately (no perm node).
    private static final List<String> SUBCOMMANDS =
            List.of("editor", "edit", "start", "generate", "place", "files", "list", "remove", "regen", "reload");
    private static final List<String> LAYOUT_MODES = List.of("fresh", "same");

    /** Reset presets, plus "now" for an immediate one-off reset. */
    private static final List<String> INTERVALS = concat(RegenInterval.labels(), "now");
    private static final String INTERVAL_CHOICES = String.join("|", INTERVALS);

    private static List<String> concat(List<String> base, String extra)
    {
        List<String> out = new ArrayList<>(base);
        out.add(extra);
        return List.copyOf(out);
    }

    private final LitemazicaPlugin plugin;

    public LitemazicaCommand(LitemazicaPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (args.length == 0)
        {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT))
        {
            case "editor" -> handleEditor(sender);
            case "edit" -> handleEdit(sender, args);
            case "start" -> handleStart(sender, args);
            case "generate" -> handleGenerate(sender, args);
            case "place" -> handlePlace(sender, args);
            case "files" -> handleFiles(sender);
            case "list" -> handleList(sender);
            case "remove" -> handleRemove(sender, args);
            case "regen" -> handleRegen(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    // ── editor ──────────────────────────────────────────────────────────────

    private void handleEditor(CommandSender sender)
    {
        if (!sender.hasPermission("litemazica.editor"))
        {
            denied(sender);
            return;
        }

        if (!(sender instanceof Player player))
        {
            reply(sender, ChatColor.RED, "Run /litemazica editor in-game — the maze builds where you stand.");
            return;
        }

        plugin.service().startEditor(new BukkitAudience(sender), player.getName());
    }

    // ── edit ────────────────────────────────────────────────────────────────

    private void handleEdit(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.edit"))
        {
            denied(sender);
            return;
        }

        if (!(sender instanceof Player player))
        {
            reply(sender, ChatColor.RED, "Run /litemazica edit in-game — it opens the browser editor for you.");
            return;
        }

        if (args.length < 2)
        {
            reply(sender, ChatColor.YELLOW, "Usage: /litemazica edit <id>");
            return;
        }

        plugin.service().editMaze(new BukkitAudience(sender), player.getName(), args[1]);
    }

    // ── start ───────────────────────────────────────────────────────────────

    private void handleStart(CommandSender sender, String[] args)
    {
        // Deliberately a separate node from litemazica.generate: entering a maze
        // is a player action, building one is an admin action.
        if (!sender.hasPermission("litemazica.start"))
        {
            denied(sender);
            return;
        }

        // Core teleports by player name; the console has none, and gets told so.
        String playerName = sender instanceof Player player ? player.getName() : null;
        plugin.service().start(new BukkitAudience(sender), playerName, args.length >= 2 ? args[1] : null);
    }

    // ── generate / place ──────────────────────────────────────────────────────

    /** Receives the resolved anchor/facing/name once a build command has parsed its args. */
    @FunctionalInterface
    private interface BuildDispatch
    {
        void dispatch(String worldName, int x, int y, int z, float yaw, String exemptName, String name);
    }

    private void handleGenerate(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.generate"))
        {
            denied(sender);
            return;
        }

        handleBuild(sender, args, "generate", "code",
                (worldName, x, y, z, yaw, exempt, name) -> plugin.service().generate(
                        new BukkitAudience(sender), args[1], worldName, x, y, z, yaw, exempt, name));
    }

    private void handlePlace(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.place"))
        {
            denied(sender);
            return;
        }

        handleBuild(sender, args, "place", "file",
                (worldName, x, y, z, yaw, exempt, name) -> plugin.service().place(
                        new BukkitAudience(sender), args[1], worldName, x, y, z, yaw, exempt, name));
    }

    /**
     * Shared argument handling for {@code generate <code> …} and {@code place
     * <file> …}: both take a reference in {@code args[1]}, then optional
     * {@code [x y z]} and {@code [name]}, disambiguated the same way. {@code ref}
     * names the reference ("code"/"file") for the usage text.
     */
    private void handleBuild(CommandSender sender, String[] args, String verb, String ref, BuildDispatch dispatch)
    {
        if (args.length < 2)
        {
            reply(sender, ChatColor.YELLOW, "Usage: /litemazica " + verb + " <" + ref + "> [x y z] [name]");
            return;
        }

        // Coordinates are exactly three integers, and a name can never be all
        // digits, so a trailing non-numeric argument is unambiguously the name.
        boolean hasCoords = args.length >= 5
                && MazeName.isAllDigits(args[2])
                && MazeName.isAllDigits(args[3])
                && MazeName.isAllDigits(args[4]);
        String name;

        if (hasCoords)
        {
            name = args.length >= 6 ? args[5] : null;

            if (args.length > 6)
            {
                reply(sender, ChatColor.YELLOW, "Usage: /litemazica " + verb + " <" + ref + "> [x y z] [name]");
                return;
            }
        }
        else if (args.length == 3)
        {
            if (MazeName.isAllDigits(args[2]))
            {
                reply(sender, ChatColor.RED, "Coordinates need all three: /litemazica " + verb + " <" + ref + "> <x> <y> <z>");
                return;
            }

            name = args[2];
        }
        else if (args.length == 2)
        {
            name = null;
        }
        else
        {
            reply(sender, ChatColor.RED, "Coordinates need all three: /litemazica " + verb + " <" + ref + "> <x> <y> <z> [name]");
            return;
        }

        // Fail before the network/disk round-trip rather than after it.
        String nameProblem = MazeName.checkAvailable(name, plugin.registry());

        if (nameProblem != null)
        {
            reply(sender, ChatColor.RED, nameProblem);
            return;
        }

        // Resolve the anchor (entrance point) and facing: explicit x y z, else
        // the player's feet and yaw.
        World world;
        int x;
        int y;
        int z;
        float yaw;
        // Standing in the region only gets a pass when the maze is anchored at
        // your own feet — you're in the entrance opening by construction. With
        // explicit coordinates you could be anywhere in the footprint, so no pass.
        Player exempt = null;

        if (hasCoords)
        {
            Integer px = ParseUtils.parseInt(args[2]);
            Integer py = ParseUtils.parseInt(args[3]);
            Integer pz = ParseUtils.parseInt(args[4]);

            if (px == null || py == null || pz == null)
            {
                reply(sender, ChatColor.RED, "Coordinates must be whole numbers: /litemazica " + verb + " <" + ref + "> <x> <y> <z>");
                return;
            }

            x = px;
            y = py;
            z = pz;

            if (sender instanceof Player player)
            {
                world = player.getWorld();
                yaw = player.getLocation().getYaw();
            }
            else
            {
                world = plugin.getServer().getWorlds().get(0);
                yaw = 0f; // console: default the maze to extend south
            }
        }
        else if (sender instanceof Player player)
        {
            Location loc = player.getLocation();
            world = player.getWorld();
            x = loc.getBlockX();
            y = loc.getBlockY();
            z = loc.getBlockZ();
            yaw = loc.getYaw();
            exempt = player;
        }
        else
        {
            reply(sender, ChatColor.RED, "Run this in-game, or provide coordinates: /litemazica " + verb + " <" + ref + "> <x> <y> <z>");
            return;
        }

        dispatch.dispatch(world.getName(), x, y, z, yaw, exempt == null ? null : exempt.getName(), name);
    }

    // ── files ──────────────────────────────────────────────────────────────────

    private void handleFiles(CommandSender sender)
    {
        if (!sender.hasPermission("litemazica.files"))
        {
            denied(sender);
            return;
        }

        List<String> files = plugin.service().listSchematics();

        if (files.isEmpty())
        {
            reply(sender, ChatColor.GRAY, "No .litematic files found. Drop some in the plugin's schematics folder.");
            return;
        }

        reply(sender, ChatColor.AQUA, "Available schematics (" + files.size() + "):");

        for (String f : files)
        {
            reply(sender, ChatColor.GRAY, "  " + f);
        }
    }

    // ── list / remove / regen / reload ────────────────────────────────────────

    private void handleList(CommandSender sender)
    {
        if (!sender.hasPermission("litemazica.list"))
        {
            denied(sender);
            return;
        }

        List<PlacedMaze> mazes = plugin.registry().all();

        if (mazes.isEmpty())
        {
            reply(sender, ChatColor.GRAY, "No mazes are currently placed.");
            return;
        }

        reply(sender, ChatColor.AQUA, "Placed mazes (" + mazes.size() + "):");

        for (PlacedMaze m : mazes)
        {
            reply(sender, ChatColor.GRAY, "  " + m.id() + " · " + m.worldName()
                    + " @(" + m.anchorX() + "," + m.anchorY() + "," + m.anchorZ() + ") "
                    + m.sizeString() + " · regen " + m.regenSummary());
        }
    }

    private void handleRemove(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.remove"))
        {
            denied(sender);
            return;
        }

        if (args.length < 2)
        {
            reply(sender, ChatColor.YELLOW, "Usage: /litemazica remove <id> [confirm]");
            return;
        }

        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        plugin.service().remove(new BukkitAudience(sender), args[1], confirmed);
    }

    private void handleRegen(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.regen"))
        {
            denied(sender);
            return;
        }

        if (args.length < 3)
        {
            reply(sender, ChatColor.YELLOW, "Usage: /litemazica regen <id> <" + INTERVAL_CHOICES + "> [fresh|same]");
            return;
        }

        Boolean fresh = null;

        if (args.length >= 4)
        {
            String mode = args[3].toLowerCase(Locale.ROOT);

            if (mode.equals("fresh"))
            {
                fresh = true;
            }
            else if (mode.equals("same"))
            {
                fresh = false;
            }
            else
            {
                reply(sender, ChatColor.RED, "Layout must be 'fresh' or 'same'.");
                return;
            }
        }

        String when = args[2].toLowerCase(Locale.ROOT);

        // "now" resets immediately instead of changing the schedule; any layout
        // argument applies to that one run and isn't saved.
        if (when.equals("now"))
        {
            plugin.service().regenerateNow(new BukkitAudience(sender), args[1], fresh);
            return;
        }

        RegenInterval interval = RegenInterval.fromLabel(when);
        // A bare number of minutes still works, for fine-tuning and for testing
        // the scheduler without waiting an hour.
        Integer minutes = interval != null ? interval.minutes() : ParseUtils.parseInt(when);

        if (minutes == null || minutes < 0)
        {
            reply(sender, ChatColor.RED, "Unknown interval '" + args[2] + "'. Use one of: " + INTERVAL_CHOICES + ".");
            return;
        }

        plugin.service().setRegen(new BukkitAudience(sender), args[1], minutes, fresh);
    }

    private void handleReload(CommandSender sender)
    {
        if (!sender.hasPermission("litemazica.reload"))
        {
            denied(sender);
            return;
        }

        plugin.reloadPlugin();
        reply(sender, ChatColor.GREEN, "Litemazica config reloaded. API base URL: " + plugin.client().baseUrl());
    }

    // ── help / tab completion / helpers ──────────────────────────────────────

    private void sendHelp(CommandSender sender)
    {
        reply(sender, ChatColor.AQUA, "Litemazica commands:");
        reply(sender, ChatColor.GRAY, "  /litemazica editor                          - design a maze in the browser, then Apply");
        reply(sender, ChatColor.GRAY, "  /litemazica edit <id>                       - re-open a placed maze in the editor and rebuild it");
        reply(sender, ChatColor.GRAY, "  /litemazica start [id]                      - teleport to a maze entrance");
        reply(sender, ChatColor.GRAY, "  /litemazica generate <code> [x y z] [name] - build a maze, optionally named");
        reply(sender, ChatColor.GRAY, "  /litemazica place <file> [x y z] [name]    - build a maze from a .litematic file");
        reply(sender, ChatColor.GRAY, "  /litemazica files                           - list available .litematic files");
        reply(sender, ChatColor.GRAY, "  /litemazica list                            - list placed mazes");
        reply(sender, ChatColor.GRAY, "  /litemazica remove <id>                     - remove a placed maze");
        reply(sender, ChatColor.GRAY, "  /litemazica regen <id> <" + INTERVAL_CHOICES + "> [fresh|same]");
        reply(sender, ChatColor.GRAY, "        set the reset schedule, or 'now' to reset immediately");
        reply(sender, ChatColor.GRAY, "  /litemazica reload                          - reload the config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args)
    {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (args.length == 1)
        {
            String prefix = sub;
            List<String> out = new ArrayList<>();

            for (String s : SUBCOMMANDS)
            {
                if (s.startsWith(prefix) && sender.hasPermission("litemazica." + s))
                {
                    out.add(s);
                }
            }

            if ("help".startsWith(prefix))
            {
                out.add("help");
            }

            return out;
        }

        if (sub.equals("generate"))
        {
            return completeGenerate(sender, args);
        }

        if (sub.equals("place"))
        {
            return completePlace(sender, args);
        }

        // start <id> / edit <id> / remove <id> / regen <id> — suggest placed maze ids.
        if (args.length == 2 && (sub.equals("start") || sub.equals("edit") || sub.equals("remove") || sub.equals("regen"))
                && sender.hasPermission("litemazica." + sub))
        {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();

            for (PlacedMaze m : plugin.registry().all())
            {
                if (m.id().toLowerCase(Locale.ROOT).startsWith(prefix))
                {
                    ids.add(m.id());
                }
            }

            // With nothing placed there is nothing to match, and an empty list
            // leaves the player staring at a blank prompt — name the argument.
            return ids.isEmpty() ? hint(args[1], "<maze-id>") : ids;
        }
        // remove <id> confirm — offer the confirm keyword.
        if (args.length == 3 && sub.equals("remove") && sender.hasPermission("litemazica.remove"))
        {
            return "confirm".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        }

        // regen <id> <interval> — suggest the presets and "now".
        if (args.length == 3 && sub.equals("regen") && sender.hasPermission("litemazica.regen"))
        {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();

            for (String interval : INTERVALS)
            {
                if (interval.startsWith(prefix))
                {
                    out.add(interval);
                }
            }

            return out;
        }

        // regen <id> <interval> [fresh|same] — suggest the layout mode.
        if (args.length == 4 && sub.equals("regen") && sender.hasPermission("litemazica.regen"))
        {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();

            for (String mode : LAYOUT_MODES)
            {
                if (mode.startsWith(prefix))
                {
                    out.add(mode);
                }
            }

            return out;
        }

        return List.of();
    }

    /**
     * Completions for {@code generate <code> [x y z] [name]}. There is nothing
     * real to suggest for a share code, so the argument names itself; the
     * coordinates suggest where the player is actually standing, which is the
     * value they usually want.
     *
     * <p>Placeholders avoid spaces on purpose: the client splits completions on
     * whitespace, so "&lt;litemazica code&gt;" would insert as two arguments.
     */
    private List<String> completeGenerate(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.generate"))
        {
            return List.of();
        }

        if (args.length == 2)
        {
            return hint(args[1], "<litemazica-code>");
        }

        return completeBuildTail(sender, args);
    }

    /** As {@link #completeGenerate}, but the first argument is a schematic file name. */
    private List<String> completePlace(CommandSender sender, String[] args)
    {
        if (!sender.hasPermission("litemazica.place"))
        {
            return List.of();
        }

        if (args.length == 2)
        {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();

            for (String f : plugin.service().listSchematics())
            {
                if (f.toLowerCase(Locale.ROOT).startsWith(prefix))
                {
                    out.add(f);
                }
            }

            // Nothing dropped in the folder yet — name the argument rather than
            // leave a blank prompt.
            return out.isEmpty() ? hint(args[1], "<file>") : out;
        }

        return completeBuildTail(sender, args);
    }

    /**
     * The {@code [x y z] [name]} tail shared by generate and place completion.
     * Three integers means explicit coordinates; anything else means the
     * argument after the reference is the maze name.
     */
    private List<String> completeBuildTail(CommandSender sender, String[] args)
    {
        boolean coordPath = ParseUtils.parseInt(args[2]) != null;

        if (args.length == 3)
        {
            if (!args[2].isEmpty())
            {
                return List.of();
            }

            // Ambiguous position: either the first coordinate or the name.
            List<String> out = new ArrayList<>(coordinate(sender, 0, "<x>"));
            out.add("<name>");
            return out;
        }

        if (args.length == 4 && coordPath)
        {
            return args[3].isEmpty() ? coordinate(sender, 1, "<y>") : List.of();
        }

        if (args.length == 5 && coordPath)
        {
            return args[4].isEmpty() ? coordinate(sender, 2, "<z>") : List.of();
        }

        if (args.length == 6 && coordPath)
        {
            return hint(args[5], "<name>");
        }

        return List.of();
    }

    /** The sender's own block coordinate on an axis, or a placeholder off-console. */
    private List<String> coordinate(CommandSender sender, int axis, String placeholder)
    {
        if (sender instanceof Player player)
        {
            Location loc = player.getLocation();
            int value = switch (axis)
            {
                case 0 -> loc.getBlockX();
                case 1 -> loc.getBlockY();
                default -> loc.getBlockZ();
            };
            return List.of(String.valueOf(value));
        }

        return List.of(placeholder);
    }

    /** Names an argument when there's nothing concrete to suggest. */
    private static List<String> hint(String partial, String placeholder)
    {
        return partial.isEmpty() ? List.of(placeholder) : List.of();
    }

    private void denied(CommandSender sender)
    {
        reply(sender, ChatColor.RED, "You don't have permission to do that.");
    }

    private void reply(CommandSender sender, ChatColor color, String text)
    {
        sender.sendMessage(color + text);
    }
}
