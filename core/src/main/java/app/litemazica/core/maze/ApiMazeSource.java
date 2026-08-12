package app.litemazica.core.maze;

import app.litemazica.core.api.LitemazicaClient;
import app.litemazica.core.api.MazeSchematic;

import java.io.IOException;

/** A maze fetched from the Litemazica API by share code — the original source. */
public final class ApiMazeSource implements MazeSource
{
    private final LitemazicaClient client;
    private final String code;

    public ApiMazeSource(LitemazicaClient client, String code)
    {
        this.client = client;
        this.code = code;
    }

    @Override
    public MazeSchematic load(String seed) throws IOException, InterruptedException
    {
        return client.fetch(code, seed);
    }

    @Override
    public boolean supportsFreshLayout()
    {
        return true;
    }

    @Override
    public String type()
    {
        return TYPE_API;
    }

    @Override
    public String reference()
    {
        return code;
    }
}
