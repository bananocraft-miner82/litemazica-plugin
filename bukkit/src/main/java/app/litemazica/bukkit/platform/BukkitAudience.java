package app.litemazica.bukkit.platform;

import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Sends core's messages to a Bukkit sender, mapping intent to legacy colours. */
public record BukkitAudience(CommandSender sender) implements Audience
{
    @Override
    public void send(MessageStyle style, String text)
    {
        sender.sendMessage(colourFor(style) + text);
    }

    @Override
    public void sendLink(MessageStyle style, String label, String url)
    {
        // Only players render interactive chat; the console gets plain text.
        if (!(sender instanceof Player player))
        {
            Audience.super.sendLink(style, label, url);
            return;
        }

        TextComponent component = new TextComponent(label);
        component.setColor(colourFor(style).asBungee());
        component.setUnderlined(true);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Open " + url)));
        player.spigot().sendMessage(component);
    }

    private static ChatColor colourFor(MessageStyle style)
    {
        return switch (style)
        {
            case SUCCESS -> ChatColor.GREEN;
            case WARNING -> ChatColor.YELLOW;
            case ERROR -> ChatColor.RED;
            case HEADING -> ChatColor.AQUA;
            default -> ChatColor.GRAY;
        };
    }
}
