package app.litemazica.fabric.command;

import app.litemazica.core.maze.MazeName;
import app.litemazica.core.maze.MazeService;
import app.litemazica.core.maze.PlacedMaze;
import app.litemazica.core.maze.RegenInterval;
import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;
import app.litemazica.fabric.LitemazicaMod;
import app.litemazica.fabric.platform.FabricAudience;
import app.litemazica.fabric.platform.FabricPlatform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * The {@code /litemazica} command tree.
 *
 * <p>Only parsing and permissions live here — everything else is
 * {@link MazeService} in core. Fabric has no permission system, so the split
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
    private static final SuggestionProvider<ServerCommandSource> MAZE_IDS = (context, builder) ->
    {
        MazeService service = LitemazicaMod.service();

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
    private static final SuggestionProvider<ServerCommandSource> SCHEMATIC_FILES = (context, builder) ->
    {
        MazeService service = LitemazicaMod.service();

        if (service != null)
        {
            for (String file : service.listSchematics())
            {
                builder.suggest(file);
            }
        }

        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher)
    {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("litemazica")
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
            dispatcher.register(CommandManager.literal(alias).redirect(registered));
        }
    }

    // ── editor / edit ──────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<ServerCommandSource> editorNode()
    {
        // Building a maze is an admin action, whether by code or via the editor.
        return CommandManager.literal("editor")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
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

    private static LiteralArgumentBuilder<ServerCommandSource> editNode()
    {
        return CommandManager.literal("edit")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
                .then(CommandManager.argument("id", StringArgumentType.word())
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

    private static LiteralArgumentBuilder<ServerCommandSource> startNode()
    {
        // No permission gate: entering a maze is a player action, not an admin one.
        return CommandManager.literal("start")
                .executes(context -> start(context, null))
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(MAZE_IDS)
                        .executes(context -> start(context, StringArgumentType.getString(context, "id"))));
    }

    private static int start(CommandContext<ServerCommandSource> context, String id)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        ServerPlayerEntity player = context.getSource().getPlayer();
        service.start(audience(context), player == null ? null : player.getGameProfile().getName(), id);
        return 1;
    }

    // ── generate ─────────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<ServerCommandSource> generateNode()
    {
        return CommandManager.literal("generate")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
                .then(CommandManager.argument("code", StringArgumentType.word())
                        .executes(context -> generate(context, null, null))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> generate(context,
                                        StringArgumentType.getString(context, "name"), null)))
                        .then(CommandManager.argument("pos", net.minecraft.command.argument.BlockPosArgumentType.blockPos())
                                .executes(context -> generate(context, null,
                                        net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(context, "pos")))
                                .then(CommandManager.argument("named", StringArgumentType.word())
                                        .executes(context -> generate(context,
                                                StringArgumentType.getString(context, "named"),
                                                net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(context, "pos"))))));
    }

    private static int generate(CommandContext<ServerCommandSource> context, String name, BlockPos explicitPos)
    {
        String code = StringArgumentType.getString(context, "code");
        return build(context, name, explicitPos, "generate",
                (service, audience, worldName, x, y, z, yaw, exempt) ->
                        service.generate(audience, code, worldName, x, y, z, yaw, exempt, name));
    }

    // ── place / files ────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<ServerCommandSource> placeNode()
    {
        return CommandManager.literal("place")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
                .then(CommandManager.argument("file", StringArgumentType.word())
                        .suggests(SCHEMATIC_FILES)
                        .executes(context -> place(context, null, null))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> place(context,
                                        StringArgumentType.getString(context, "name"), null)))
                        .then(CommandManager.argument("pos", net.minecraft.command.argument.BlockPosArgumentType.blockPos())
                                .executes(context -> place(context, null,
                                        net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(context, "pos")))
                                .then(CommandManager.argument("named", StringArgumentType.word())
                                        .executes(context -> place(context,
                                                StringArgumentType.getString(context, "named"),
                                                net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(context, "pos"))))));
    }

    private static int place(CommandContext<ServerCommandSource> context, String name, BlockPos explicitPos)
    {
        String file = StringArgumentType.getString(context, "file");
        return build(context, name, explicitPos, "place",
                (service, audience, worldName, x, y, z, yaw, exempt) ->
                        service.place(audience, file, worldName, x, y, z, yaw, exempt, name));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> filesNode()
    {
        return CommandManager.literal("files")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
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

    private static int build(CommandContext<ServerCommandSource> context, String name, BlockPos explicitPos,
                             String verb, BuildDispatch dispatch)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        ServerCommandSource source = context.getSource();
        Audience audience = audience(context);

        String nameProblem = MazeName.checkAvailable(name, service.registry());

        if (nameProblem != null)
        {
            audience.send(MessageStyle.ERROR, nameProblem);
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        String worldName = FabricPlatform.nameOf(source.getWorld());
        float yaw = player != null ? player.getYaw() : 0f;
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
            anchor = player.getBlockPos();
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

    private static LiteralArgumentBuilder<ServerCommandSource> listNode()
    {
        return CommandManager.literal("list")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
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

    private static LiteralArgumentBuilder<ServerCommandSource> removeNode()
    {
        return CommandManager.literal("remove")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(MAZE_IDS)
                        .executes(context -> remove(context, false))
                        .then(CommandManager.literal("confirm")
                                .executes(context -> remove(context, true))));
    }

    private static int remove(CommandContext<ServerCommandSource> context, boolean confirmed)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        service.remove(audience(context), StringArgumentType.getString(context, "id"), confirmed);
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> regenNode()
    {
        LiteralArgumentBuilder<ServerCommandSource> regen = CommandManager.literal("regen")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL));

        var idArg = CommandManager.argument("id", StringArgumentType.word()).suggests(MAZE_IDS);

        // "now" resets immediately; the presets change the schedule.
        idArg.then(CommandManager.literal("now")
                .executes(context -> regenNow(context, null))
                .then(CommandManager.literal("fresh").executes(context -> regenNow(context, true)))
                .then(CommandManager.literal("same").executes(context -> regenNow(context, false))));

        for (RegenInterval interval : RegenInterval.values())
        {
            idArg.then(CommandManager.literal(interval.label())
                    .executes(context -> setRegen(context, interval, null))
                    .then(CommandManager.literal("fresh")
                            .executes(context -> setRegen(context, interval, true)))
                    .then(CommandManager.literal("same")
                            .executes(context -> setRegen(context, interval, false))));
        }

        return regen.then(idArg);
    }

    private static int setRegen(CommandContext<ServerCommandSource> context, RegenInterval interval, Boolean fresh)
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

    private static int regenNow(CommandContext<ServerCommandSource> context, Boolean fresh)
    {
        MazeService service = service(context);

        if (service == null)
        {
            return 0;
        }

        service.regenerateNow(audience(context), StringArgumentType.getString(context, "id"), fresh);
        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> reloadNode()
    {
        return CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(ADMIN_LEVEL))
                .executes(context ->
                {
                    LitemazicaMod.reload();
                    MazeService service = LitemazicaMod.service();
                    audience(context).send(MessageStyle.SUCCESS, "Litemazica config reloaded."
                            + (service == null ? "" : " API base URL: " + service.client().baseUrl()));
                    return 1;
                });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Audience audience(CommandContext<ServerCommandSource> context)
    {
        return new FabricAudience(context.getSource());
    }

    /** The command sender's player name, or null when run from the console. */
    private static String playerName(CommandContext<ServerCommandSource> context)
    {
        ServerPlayerEntity player = context.getSource().getPlayer();
        return player == null ? null : player.getGameProfile().getName();
    }

    /** Null (with a message) before the server has finished starting. */
    private static MazeService service(CommandContext<ServerCommandSource> context)
    {
        MazeService service = LitemazicaMod.service();

        if (service == null)
        {
            audience(context).send(MessageStyle.ERROR, "Litemazica is not ready yet.");
        }

        return service;
    }
}
