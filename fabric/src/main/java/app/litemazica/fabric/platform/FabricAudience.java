package app.litemazica.fabric.platform;

import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Sends core's messages to a Brigadier command source. */
public record FabricAudience(ServerCommandSource source) implements Audience
{
    @Override
    public void send(MessageStyle style, String text)
    {
        Text message = Text.literal(text).formatted(formattingFor(style));

        if (style == MessageStyle.ERROR)
        {
            source.sendError(message);
            return;
        }

        // Never broadcast to ops: maze admin chatter is for whoever asked.
        source.sendFeedback(() -> message, false);
    }

    @Override
    public void sendLink(MessageStyle style, String label, String url)
    {
        Text message = Text.literal(label).styled(s -> s
                .withFormatting(formattingFor(style))
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Open " + url))));
        source.sendFeedback(() -> message, false);
    }

    private static Formatting formattingFor(MessageStyle style)
    {
        return switch (style)
        {
            case SUCCESS -> Formatting.GREEN;
            case WARNING -> Formatting.YELLOW;
            case ERROR -> Formatting.RED;
            case HEADING -> Formatting.AQUA;
            default -> Formatting.GRAY;
        };
    }
}
