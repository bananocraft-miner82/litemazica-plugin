package app.litemazica.neoforge.command;

import app.litemazica.core.maze.MazeName;
import app.litemazica.core.maze.MazeService;
import app.litemazica.core.maze.PlacedMaze;
import app.litemazica.core.maze.RegenInterval;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.neoforge.LitemazicaNeoForgeMod;
import app.litemazica.neoforge.platform.NeoForgeAudience;
import app.litemazica.neoforge.platform.NeoForgePlatform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /litemazica} command tree.
 *
 * <p>Only parsing and permissions live here — everything else is
 * {@link MazeService} in core. NeoForge has no permission system, so the split
 * that is {@code litemazica.start} vs {@code litemazica.generate} on Bukkit is
 * expressed as op level 0 (anyone) vs op level 2 (admin).
 */
public final class LitemazicaCommands
{
    /** Vanilla op level required for the admin subcommands. */
    private static final int ADMIN_LEVEL = 2;

    private LitemazicaCommands()
    {
    }

    /** Suggests the ids of currently placed mazes. */
    private static final SuggestionProvider<CommandSourceStack> MAZE_IDS = (context, builder) ->
    {
        MazeService service = LitemazicaNeoForgeMod.service();

        if (service != null)
        {
            for (PlacedMaze maze : service.registry().all())
            {
                builder.suggest(maze.id());
            }
        }

        return builder.buildFuture();
    };

    /** Suggests the {@code .litematic} files available to {@code place}. */
    private static final SuggestionProvider<CommandSourceStack> SCHEMATIC_FILES = (context, builder) ->
    {
        MazeService service = LitemazicaNeoForgeMod.service();

        if (service != null)
        {
            for (String file : service.listSchematics())
            {
                builder.suggest(file);
            }
        }

        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("litemazica")
                .then(editorNode())
                .then(editNode())
                .then(startNode())
                .then(generateNode())
                .then(placeNode())
                .then(filesNode())
                .then(listNode())
                .then(removeNode())
                .then(regenNode())
                .then(reloadNode());

        var registered = dispatcher.register(root);

        // Same aliases as the Bukkit plugin.
        for (String alias : new String[]{"lmz", "maze"})
        {
            dispatcher.register(Commands.literal(alias).redirect(registered));
        }
    }

    // ── editor / edit ──────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> editorNode()
    {
        // Building a maze is an admin action, whether by code or via the editor.
        return Commands.literal("editor")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .executes(context ->
                {
                    MazeService service = service(context);

                    if (service == null)
                    {
                        return 0;
                    }

                    service.startEditor(audience(context), playerName(context));
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> editNode()
    {
        return Commands.literal("edit")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(MAZE_IDS)
                        .executes(context ->
                        {
                            MazeService service = service(context);

                            if (service == null)
                            {
                                return 0;
                            }

                            service.editMaze(audience(context), playerName(context),
                                    StringArgumentType.getString(context, "id"));
                            return 1;
                        }));
    }

    // ── start ────────────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> startNode()
    {
        // No permission gate: entering a maze is a player action, not an admin one.
        return Commands.literal("start")
                .executes(context -> start(context, null))
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(MAZE_IDS)
                        .executes(context -> start(context, StringArgumentType.getString(context, "id"))));
    }

    private static int start(CommandContext<CommandSourceStack> context, String id)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        ServerPlayer player = context.getSource().getPlayer();
        service.start(audience(context), player == null ? null : player.getGameProfile().getName(), id);
        return 1;
    }

    // ── generate ─────────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> generateNode()
    {
        return Commands.literal("generate")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(context -> generate(context, null, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> generate(context,
                                        StringArgumentType.getString(context, "name"), null)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> generate(context, null,
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")))
                                .then(Commands.argument("named", StringArgumentType.word())
                                        .executes(context -> generate(context,
                                                StringArgumentType.getString(context, "named"),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"))))));
    }

    private static int generate(CommandContext<CommandSourceStack> context, String name, BlockPos explicitPos)
    {
        String code = StringArgumentType.getString(context, "code");
        return build(context, name, explicitPos, "generate",
                (service, audience, worldName, x, y, z, yaw, exempt) ->
                        service.generate(audience, code, worldName, x, y, z, yaw, exempt, name));
    }

    // ── place / files ────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> placeNode()
    {
        return Commands.literal("place")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .then(Commands.argument("file", StringArgumentType.word())
                        .suggests(SCHEMATIC_FILES)
                        .executes(context -> place(context, null, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> place(context,
                                        StringArgumentType.getString(context, "name"), null)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> place(context, null,
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")))
                                .then(Commands.argument("named", StringArgumentType.word())
                                        .executes(context -> place(context,
                                                StringArgumentType.getString(context, "named"),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"))))));
    }

    private static int place(CommandContext<CommandSourceStack> context, String name, BlockPos explicitPos)
    {
        String file = StringArgumentType.getString(context, "file");
        return build(context, name, explicitPos, "place",
                (service, audience, worldName, x, y, z, yaw, exempt) ->
                        service.place(audience, file, worldName, x, y, z, yaw, exempt, name));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> filesNode()
    {
        return Commands.literal("files")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .executes(context ->
                {
                    MazeService service = service(context);

                    if (service == null)
                    {
                        return 0;
                    }

                    Audience audience = audience(context);
                    var files = service.listSchematics();

                    if (files.isEmpty())
                    {
                        audience.send(MessageStyle.INFO,
                                "No .litematic files found. Drop some in the mod's schematics folder.");
                        return 1;
                    }

                    audience.send(MessageStyle.HEADING, "Available schematics (" + files.size() + "):");

                    for (String file : files)
                    {
                        audience.send(MessageStyle.INFO, "  " + file);
                    }

                    return 1;
                });
    }

    // ── shared build (generate + place) ───────────────────────────────────────

    /** Dispatches to {@code generate} or {@code place} once the anchor is resolved. */
    @FunctionalInterface
    private interface BuildDispatch
    {
        void dispatch(MazeService service, Audience audience, String worldName,
                      int x, int y, int z, float yaw, String exemptName);
    }

    private static int build(CommandContext<CommandSourceStack> context, String name, BlockPos explicitPos,
                             String verb, BuildDispatch dispatch)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        CommandSourceStack source = context.getSource();
        Audience audience = audience(context);

        String nameProblem = MazeName.checkAvailable(name, service.registry());

        if (nameProblem != null)
        {
            audience.send(MessageStyle.ERROR, nameProblem);
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        String worldName = NeoForgePlatform.nameOf(source.getLevel());
        float yaw = player != null ? player.getYRot() : 0f;
        BlockPos anchor;
        String exempt;

        if (explicitPos != null)
        {
            // Explicit coordinates: nobody gets a pass on the presence check.
            anchor = explicitPos;
            exempt = null;
        }
        else if (player != null)
        {
            anchor = player.blockPosition();
            exempt = player.getGameProfile().getName();
        }
        else
        {
            audience.send(MessageStyle.ERROR,
                    "Run this in-game, or provide coordinates: /litemazica " + verb + " <ref> <x> <y> <z>");
            return 0;
        }

        dispatch.dispatch(service, audience, worldName,
                anchor.getX(), anchor.getY(), anchor.getZ(), yaw, exempt);
        return 1;
    }

    // ── list / remove / regen / reload ───────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> listNode()
    {
        return Commands.literal("list")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .executes(context ->
                {
                    MazeService service = service(context);

                    if (service == null)
                    {
                        return 0;
                    }

                    Audience audience = audience(context);
                    var mazes = service.registry().all();

                    if (mazes.isEmpty())
                    {
                        audience.send(MessageStyle.INFO, "No mazes are currently placed.");
                        return 1;
                    }

                    audience.send(MessageStyle.HEADING, "Placed mazes (" + mazes.size() + "):");

                    for (PlacedMaze maze : mazes)
                    {
                        audience.send(MessageStyle.INFO, "  " + maze.id() + " · " + maze.worldName()
                                + " @(" + maze.anchorX() + "," + maze.anchorY() + "," + maze.anchorZ() + ") "
                                + maze.sizeString() + " · regen " + maze.regenSummary());
                    }

                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> removeNode()
    {
        return Commands.literal("remove")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(MAZE_IDS)
                        .executes(context -> remove(context, false))
                        .then(Commands.literal("confirm")
                                .executes(context -> remove(context, true))));
    }

    private static int remove(CommandContext<CommandSourceStack> context, boolean confirmed)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        service.remove(audience(context), StringArgumentType.getString(context, "id"), confirmed);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> regenNode()
    {
        LiteralArgumentBuilder<CommandSourceStack> regen = Commands.literal("regen")
                .requires(source -> source.hasPermission(ADMIN_LEVEL));

        RequiredArgumentBuilder<CommandSourceStack, String> idArg =
                Commands.argument("id", StringArgumentType.word()).suggests(MAZE_IDS);

        // "now" resets immediately; the presets change the schedule.
        idArg.then(Commands.literal("now")
                .executes(context -> regenNow(context, null))
                .then(Commands.literal("fresh").executes(context -> regenNow(context, true)))
                .then(Commands.literal("same").executes(context -> regenNow(context, false))));

        for (RegenInterval interval : RegenInterval.values())
        {
            idArg.then(Commands.literal(interval.label())
                    .executes(context -> setRegen(context, interval, null))
                    .then(Commands.literal("fresh")
                            .executes(context -> setRegen(context, interval, true)))
                    .then(Commands.literal("same")
                            .executes(context -> setRegen(context, interval, false))));
        }

        return regen.then(idArg);
    }

    private static int setRegen(CommandContext<CommandSourceStack> context, RegenInterval interval, Boolean fresh)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        service.setRegen(audience(context), StringArgumentType.getString(context, "id"),
                interval.minutes(), fresh);
        return 1;
    }

    private static int regenNow(CommandContext<CommandSourceStack> context, Boolean fresh)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        service.regenerateNow(audience(context), StringArgumentType.getString(context, "id"), fresh);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> reloadNode()
    {
        return Commands.literal("reload")
                .requires(source -> source.hasPermission(ADMIN_LEVEL))
                .executes(context ->
                {
                    LitemazicaNeoForgeMod.reload();
                    MazeService service = LitemazicaNeoForgeMod.service();
                    audience(context).send(MessageStyle.SUCCESS, "Litemazica config reloaded."
                            + (service == null ? "" : " API base URL: " + service.client().baseUrl()));
                    return 1;
                });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Audience audience(CommandContext<CommandSourceStack> context)
    {
        return new NeoForgeAudience(context.getSource());
    }

    /** The command sender's player name, or null when run from the console. */
    private static String playerName(CommandContext<CommandSourceStack> context)
    {
        ServerPlayer player = context.getSource().getPlayer();
        return player == null ? null : player.getGameProfile().getName();
    }

    /** Null (with a message) before the server has finished starting. */
    private static MazeService service(CommandContext<CommandSourceStack> context)
    {
        MazeService service = LitemazicaNeoForgeMod.service();

        if (service == null)
        {
            audience(context).send(MessageStyle.ERROR, "Litemazica is not ready yet.");
        }

        return service;
    }
}
