package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.api.MazeSchematic;

/**
 * A {@link LitemazicaClient} that stands in for the network: {@code fetch} returns
 * a canned schematic and the editor calls return canned session/poll results, so
 * the whole generate/regen/editor flow can run without HTTP.
 */
final class FakeClient extends LitemazicaClient
{
    MazeSchematic schematic;
    EditorSession session = new EditorSession("token", "http://test/editor", 3600);
    EditorPoll poll = new EditorPoll("pending", null);

    String lastFetchCode;
    String lastFetchSeed;

    FakeClient()
    {
        super("http://test", 1);
    }

    @Override
    public MazeSchematic fetch(String shareCode)
    {
        return fetch(shareCode, null);
    }

    @Override
    public MazeSchematic fetch(String shareCode, String seedOverride)
    {
        lastFetchCode = shareCode;
        lastFetchSeed = seedOverride;
        return schematic;
    }

    @Override
    public EditorSession newEditorSession()
    {
        return session;
    }

    @Override
    public EditorSession newEditorSession(String seedCode)
    {
        return session;
    }

    @Override
    public EditorPoll pollEditor(String token)
    {
        return poll;
    }
}
