import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.Category;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.utilities.Logger;

@ScriptManifest(name = "Script Name", author = "Developer Name",
        description = "This is the script description.",
        category = Category.WOODCUTTING, version = 1.0)
public class Main extends AbstractScript {

    @Override
    public int onLoop() {
        Logger.log("My first script!");
        return 1000;
    }

}
