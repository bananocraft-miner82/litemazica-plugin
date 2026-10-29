package app.litemazica.neoforge;

import app.litemazica.vanilla.LitemazicaRuntime;
import app.litemazica.vanilla.command.LitemazicaCommands;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side entry point for 26.x. The adapter is shared with Fabric (both see
 * Minecraft's own names from 26.1), so this only wires NeoForge's events to it.
 */
@Mod("litemazica")
public final class LitemazicaNeoForgeMod
{
    public LitemazicaNeoForgeMod()
    {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event)
    {
        LitemazicaCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event)
    {
        LitemazicaRuntime.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event)
    {
        LitemazicaRuntime.tick();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        LitemazicaRuntime.stop();
    }
}
