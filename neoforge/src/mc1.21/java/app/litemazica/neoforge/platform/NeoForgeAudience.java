package app.litemazica.neoforge.platform;

import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Sends core's messages to a Brigadier command source. */
public record NeoForgeAudience(CommandSourceStack source) implements Audience
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
        Component message = Component.literal(label).withStyle(s -> s
                .applyFormat(formattingFor(style))
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open " + url))));
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
