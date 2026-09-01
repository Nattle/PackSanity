"""Run the production Android asset installer on the JVM with filesystem-backed test APIs."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest


STUBS = {
    'android/content/Context.java': '''package android.content;
import android.content.res.AssetManager;
import java.io.File;
public abstract class Context {
 public Context getApplicationContext() { return this; }
 public abstract File getNoBackupFilesDir();
 public abstract AssetManager getAssets();
}''',
    'android/content/res/AssetManager.java': '''package android.content.res;
import java.io.*;
public abstract class AssetManager {
 public abstract String[] list(String path) throws IOException;
 public abstract InputStream open(String path) throws IOException;
}''',
    'android/os/Build.java': '''package android.os;
public class Build { public static String[] SUPPORTED_ABIS = {"arm64-v8a"}; }''',
    'android/util/Log.java': """package android.util;
public class Log {
 public static int i(String tag, String value) { return 0; }
 public static int e(String tag, String value, Throwable error) { return 0; }
}""",

}

HARNESS = '''package dev.nattle.packsanity.ut;
import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class RuntimeAssetsProbe {
 static Path assets, cache;
 static String failOnce;
 static void write(Path path, String value) throws Exception {
  Files.createDirectories(path.getParent()); Files.writeString(path, value);
 }
 static void writeArchive(String adapter) throws Exception {
  Path file = assets.resolve("packsanity/runtime.zip");
  Files.createDirectories(file.getParent());
  try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
   zip.putNextEntry(new ZipEntry("python/aarch64-linux-android/lib/python3.13/zipfile/_path/__init__.py"));
   zip.write("new-core".getBytes());
   zip.closeEntry();
   zip.putNextEntry(new ZipEntry("modules/packsanity_ut_adapter.py"));
   zip.write(adapter.getBytes());
   zip.closeEntry();
  }
 }
 static void check(boolean condition) {
  if (!condition) throw new AssertionError("asset extraction invariant failed");
 }
 static class Assets extends AssetManager {
  public String[] list(String name) throws IOException {
   File[] children = assets.resolve(name).toFile().listFiles();
   if (children == null) return new String[0];
   String[] names = new String[children.length];
   for (int i=0; i<children.length; i++) names[i] = children[i].getName();
   return names;
  }
  public InputStream open(String name) throws IOException {
   if (name.equals(failOnce)) { failOnce = null; throw new IOException("interrupted copy"); }
   return Files.newInputStream(assets.resolve(name));
  }
 }
 public static void main(String[] args) throws Exception {
  assets=Path.of(args[0],"assets"); cache=Path.of(args[0],"cache");
  String id=args[2];
  Context context = new Context() {
   public File getNoBackupFilesDir() { return cache.toFile(); }
   public AssetManager getAssets() { return new Assets(); }
  };
  PythonRuntimeAssets installer = new PythonRuntimeAssets(context);
  write(assets.resolve("packsanity/packsanity-runtime-id.txt"), id);
  writeArchive("new-adapter");
  if (args[1].equals("upgrade")) {
   String previousId = "packsanity-ut-runtime-" + (Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)) - 1);
   write(cache.resolve(previousId + "/.installed-aarch64-linux-android"), "");
   write(cache.resolve(previousId + "/modules/packsanity_ut_adapter.py"), "obsolete-adapter");
   PythonRuntimeAssets.Paths first = installer.install();
   check(Path.of(first.modulePath).equals(cache.resolve(id+"/modules")));
   check(Files.readString(Path.of(first.modulePath,"packsanity_ut_adapter.py")).equals("new-adapter"));
   check(Files.readString(Path.of(first.pythonHome,"lib/python3.13/zipfile/_path/__init__.py")).equals("new-core"));
   writeArchive("next-adapter");
   check(Files.readString(Path.of(installer.install().modulePath,"packsanity_ut_adapter.py")).equals("new-adapter"));
   write(assets.resolve("packsanity/packsanity-runtime-id.txt"), "fixture-next-runtime");
   PythonRuntimeAssets.Paths second = installer.install();
   check(Path.of(second.modulePath).equals(cache.resolve("fixture-next-runtime/modules")));
   check(Files.readString(Path.of(second.modulePath,"packsanity_ut_adapter.py")).equals("next-adapter"));
   check(Files.readString(cache.resolve(previousId + "/modules/packsanity_ut_adapter.py")).equals("obsolete-adapter"));
  } else if (args[1].equals("interrupted")) {
   failOnce = "packsanity/runtime.zip";
   try { installer.install(); throw new AssertionError("must fail copy"); }
   catch (IllegalStateException expected) {}
   check(!Files.exists(cache.resolve(id+"/.installed-aarch64-linux-android")));
   PythonRuntimeAssets.Paths paths = installer.install();
   check(Files.readString(Path.of(paths.modulePath,"packsanity_ut_adapter.py")).equals("new-adapter"));
   check(Files.exists(cache.resolve(id+"/.installed-aarch64-linux-android")));
  } else {
   if (args[1].equals("missing")) Files.delete(assets.resolve("packsanity/packsanity-runtime-id.txt"));
   try { installer.install(); throw new AssertionError("must reject runtime identifier"); }
   catch (IllegalStateException expected) { check(expected.getCause() instanceof IOException); }
   check(!Files.exists(cache));
  }
 }
}'''


class AndroidRuntimeAssetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.root = Path(cls.directory.name)
        repository = Path(__file__).resolve().parents[4]
        cls.runtime_id = json.loads((repository / 'python/runtime.lock.json').read_text())['runtimeId']
        files = []
        for name, content in STUBS.items():
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            files.append(str(path))
        harness = cls.root / 'dev/nattle/packsanity/ut/RuntimeAssetsProbe.java'
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS)
        files += [str(harness), str(repository / 'runtime-android/src/main/java/dev/nattle/packsanity/ut/PythonRuntimeAssets.java')]
        subprocess.run(['javac', '-d', str(cls.root), *files], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def probe(self, scenario, runtime_id=None):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(['java', '-cp', str(self.root), 'dev.nattle.packsanity.ut.RuntimeAssetsProbe',
                                     directory, scenario, self.runtime_id if runtime_id is None else runtime_id],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_upgrade_selects_packaged_runtime_instead_of_obsolete_extraction(self):
        self.probe('upgrade')

    def test_interrupted_install_retries_without_a_completion_marker(self):
        self.probe('interrupted')

    def test_missing_runtime_identifier_fails_instead_of_reusing_stale_code(self):
        self.probe('missing')

    def test_invalid_runtime_identifier_cannot_escape_cache_directory(self):
        for identifier in ('', '.', '..', '../outside', 'a' * 129):
            with self.subTest(identifier=identifier):
                self.probe('invalid', identifier)


if __name__ == '__main__':
    unittest.main()
