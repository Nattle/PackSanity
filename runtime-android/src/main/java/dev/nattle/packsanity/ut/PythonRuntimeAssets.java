package dev.nattle.packsanity.ut;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts immutable app assets because CPython requires ordinary filesystem paths. */
public final class PythonRuntimeAssets {
    private static final String TAG = "PackSanityUT";
    private static final Object INSTALL_LOCK = new Object();
    private final Context context;

    public PythonRuntimeAssets(Context context) { this.context = context.getApplicationContext(); }

    public Paths install() {
        synchronized (INSTALL_LOCK) {
            String host = selectHost();
            try {
                // Staging writes this from runtime.lock.json. A second hardcoded version here
                // allowed app upgrades to keep an obsolete extracted AP core/adapter payload.
                String runtimeId = readRuntimeId();
                File root = new File(context.getNoBackupFilesDir(), runtimeId);
                File pythonHome = new File(root, "python/" + host);
                File modules = new File(root, "modules");
                File marker = new File(root, ".installed-" + host);
                if (!marker.isFile()) {
                    Log.i(TAG, "Installing embedded Universal Tracker runtime " + runtimeId + " for " + host);
                    extractRuntime(root);
                    if (!new File(pythonHome, "lib/python3.13/zipfile/_path/__init__.py").isFile()
                            || !new File(modules, "packsanity_ut_adapter.py").isFile()) {
                        throw new IOException("Packaged Universal Tracker runtime is incomplete");
                    }
                    if (!marker.createNewFile() && !marker.isFile()) {
                        throw new IOException("Could not mark Universal Tracker runtime installed");
                    }
                }
                return new Paths(pythonHome.getAbsolutePath(), modules.getAbsolutePath());
            } catch (IOException error) {
                Log.e(TAG, "Embedded Universal Tracker runtime assets are missing or unreadable", error);
                throw new IllegalStateException(
                    "Embedded Universal Tracker runtime assets are missing or unreadable; " +
                    "rebuild after the required runtime inputs have been staged",
                    error
                );
            }
        }
    }

    private String readRuntimeId() throws IOException {
        try (InputStream source = context.getAssets().open("packsanity/packsanity-runtime-id.txt")) {
            byte[] buffer = new byte[129];
            int length = 0;
            int read;
            while (length < buffer.length && (read = source.read(buffer, length, buffer.length - length)) > 0) {
                length += read;
            }
            String value = new String(buffer, 0, length, StandardCharsets.UTF_8);
            if (!value.matches("[A-Za-z0-9._-]{1,128}") || value.equals(".") || value.equals("..")) {
                throw new IOException("Packaged Universal Tracker runtime identifier is invalid");
            }
            return value;
        }
    }

    private String selectHost() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return "aarch64-linux-android";
        }
        throw new IllegalStateException("Universal Tracker runtime does not support this Android ABI");
    }

    private void extractRuntime(File root) throws IOException {
        String prefix = root.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[8192];
        try (ZipInputStream archive = new ZipInputStream(context.getAssets().open("packsanity/runtime.zip"))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                File destination = new File(root, entry.getName());
                if (!destination.getCanonicalPath().startsWith(prefix) || entry.isDirectory()) {
                    throw new IOException("Invalid runtime asset entry: " + entry.getName());
                }
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Could not create " + parent);
                }
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    int read;
                    while ((read = archive.read(buffer)) != -1) output.write(buffer, 0, read);
                }
            }
        }
    }

    public static final class Paths {
        public final String pythonHome;
        public final String modulePath;
        Paths(String pythonHome, String modulePath) {
            this.pythonHome = pythonHome;
            this.modulePath = modulePath;
        }
    }
}
