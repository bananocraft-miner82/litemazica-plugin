package app.litemazica.core.maze;

import app.litemazica.core.platform.Audience;
import app.litemazica.core.platform.MessageStyle;

import java.util.ArrayList;
import java.util.List;

/** Captures every message sent, so a test can assert what a player was told. */
final class FakeAudience implements Audience
{
    record Msg(MessageStyle style, String text)
    {
    }

    final List<Msg> messages = new ArrayList<>();

    @Override
    public void send(MessageStyle style, String text)
    {
        messages.add(new Msg(style, text));
    }

    boolean sent(MessageStyle style)
    {
        return messages.stream().anyMatch(m -> m.style == style);
    }

    /** True if any message of {@code style} contains {@code substring}. */
    boolean sent(MessageStyle style, String substring)
    {
        return messages.stream().anyMatch(m -> m.style == style && m.text.contains(substring));
    }

    MessageStyle lastStyle()
    {
        return messages.get(messages.size() - 1).style;
    }

    String lastText()
    {
        return messages.get(messages.size() - 1).text;
    }
}
