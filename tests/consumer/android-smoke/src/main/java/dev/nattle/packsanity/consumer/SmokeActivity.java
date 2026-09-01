package dev.nattle.packsanity.consumer;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import dev.nattle.packsanity.feature.packruntime.data.PackSanityPackParser;
import dev.nattle.packsanity.lua.LuaNativeRuntime;
import dev.nattle.packsanity.ut.PythonNativeRuntime;
import dev.nattle.packsanity.ut.PythonRuntimeAssets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import org.json.JSONObject;

public final class SmokeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                checkArtifact();
                Log.i("PackSanitySmoke", "PACKSANITY_ANDROID_OK");
            } catch (Throwable error) {
                Log.e("PackSanitySmoke", "PACKSANITY_ANDROID_FAILED", error);
            }
        }).start();
    }

    private void checkArtifact() throws Exception {
        new PackSanityPackParser();
        try (LuaNativeRuntime lua = new LuaNativeRuntime(8 * 1024 * 1024, 100_000)) {
            lua.execute("function answer(_) return 42 end", "@consumer");
            if (lua.callInteger("answer", "") != 42) throw new AssertionError("Lua result");
        }

        File world = new File(getFilesDir(), "tiny_world.apworld");
        try (InputStream source = getAssets().open("tiny_world.apworld");
             FileOutputStream destination = new FileOutputStream(world)) {
            source.transferTo(destination);
        }
        PythonRuntimeAssets installer = new PythonRuntimeAssets(this);
        PythonRuntimeAssets.Paths paths = installer.install();
        File adapter = new File(paths.modulePath, "packsanity_ut_adapter.py");
        long modified = adapter.lastModified();
        if (!installer.install().modulePath.equals(paths.modulePath) || modified != adapter.lastModified()) {
            throw new AssertionError("Asset cache reuse");
        }
        for (int lifecycle = 0; lifecycle < 2; lifecycle++) {
            try (PythonNativeRuntime python = new PythonNativeRuntime(paths.pythonHome, paths.modulePath)) {
                python.loadWorld(new JSONObject().put("apworld_path", world.getAbsolutePath())
                    .put("package_root", "tiny_world").toString());
                python.initializeTracker("{\"game\":\"PackSanity World\",\"slot\":1,\"slot_name\":\"Smoke\",\"slot_data\":{}}");
                String update = python.updateState("{\"missing_locations\":[],\"checked_locations\":[2001],"
                    + "\"received_items\":[{\"item_id\":1001,\"location_id\":2001,\"player\":1,\"flags\":1}]}");
                JSONObject snapshot = new JSONObject(update);
                JSONObject chest = snapshot.getJSONArray("locations").getJSONObject(0);
                JSONObject key = snapshot.getJSONArray("inventory").getJSONObject(0);
                if (chest.getInt("id") != 2001 || !chest.getBoolean("checked") || key.getInt("count") != 1) {
                    throw new AssertionError("Unexpected tracker state " + update);
                }
            }
        }
    }
}
