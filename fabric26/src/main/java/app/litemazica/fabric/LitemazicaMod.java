package app.litemazica.fabric;

import app.litemazica.vanilla.LitemazicaRuntime;
import app.litemazica.vanilla.command.LitemazicaCommands;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Server-side entry point for 26.x. The adapter is shared with NeoForge (both
 * see Minecraft's own names from 26.1), so this only wires Fabric's events to it.
 */
public final class LitemazicaMod implements DedicatedServerModInitializer
{
    @Override
    public void onInitializeServer()
    {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> LitemazicaCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(LitemazicaRuntime::start);
        ServerTickEvents.END_SERVER_TICK.register(server -> LitemazicaRuntime.tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LitemazicaRuntime.stop());
    }
}
