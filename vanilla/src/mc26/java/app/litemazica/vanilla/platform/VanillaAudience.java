package app.litemazica.vanilla.platform;

import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;

/** Sends core's messages to a Brigadier command source. */
public record VanillaAudience(CommandSourceStack source) implements Audience
{
    @Override
    public void send(MessageStyle style, String text)
    {
        Component message = Component.literal(text).withStyle(formattingFor(style));

        if (style == MessageStyle.ERROR)
        {
            source.sendFailure(message);
            return;
        }

        // Never broadcast to ops: maze admin chatter is for whoever asked.
        source.sendSuccess(() -> message, false);
    }

    @Override
    public void sendLink(MessageStyle style, String label, String url)
    {
        URI uri;

        try
        {
            uri = URI.create(url);
        }
        catch (IllegalArgumentException e)
        {
            send(style, label + ": " + url); // not a link a client would open anyway
            return;
        }

        Component message = Component.literal(label).withStyle(s -> s
                .applyFormat(formattingFor(style))
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(uri))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open " + url))));
        source.sendSuccess(() -> message, false);
    }

    private static ChatFormatting formattingFor(MessageStyle style)
    {
        return switch (style)
        {
            case SUCCESS -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.YELLOW;
            case ERROR -> ChatFormatting.RED;
            case HEADING -> ChatFormatting.AQUA;
            default -> ChatFormatting.GRAY;
        };
    }
}
