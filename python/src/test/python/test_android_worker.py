"""Exercise production Java ownership with a controllable JNI boundary, never a device fake."""
from pathlib import Path
import os
import shlex
import subprocess
import sys
import tempfile
import unittest


HARNESS = '''package dev.nattle.packsanity.ut;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WorkerProbe {
 static volatile String blocked, failing;
 static CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
 static AtomicInteger live = new AtomicInteger();
 static volatile Throwable error;
 static volatile boolean interrupted;
 static volatile PythonNativeRuntime runtime;
 static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
 public static void boundary(String operation) throws Exception {
  if (operation.equals(blocked)) {
   entered.countDown();
   check(release.await(5, TimeUnit.SECONDS), "boundary release timed out");
  }
  if (operation.equals(failing)) throw new IllegalStateException("native failure");
 }
 public static long created() throws Exception { boundary("create"); live.incrementAndGet(); return 1; }
 public static void closed() throws Exception { boundary("close"); live.decrementAndGet(); }
 public static String called() throws Exception { boundary("call"); return "result"; }
 public static void main(String[] args) throws Exception {
  blocked = args[0];
  if (args.length > 1) failing = blocked;
  if (!blocked.equals("create")) runtime = new PythonNativeRuntime("home", "modules");
  Thread caller = new Thread(() -> {
   try {
    if (blocked.equals("create")) runtime = new PythonNativeRuntime("home", "modules");
    else if (blocked.equals("close")) runtime.close();
    else check(runtime.updateState("{}").equals("result"), "result lost");
   } catch (Throwable value) { error = value; }
   finally { interrupted = Thread.currentThread().isInterrupted(); }
  });
  caller.start();
  check(entered.await(5, TimeUnit.SECONDS), "worker never started");
  caller.interrupt();
  caller.join(100);
  boolean abandoned = !caller.isAlive();
  release.countDown();
  caller.join(5000);
  check(!caller.isAlive(), "caller hung");
  // Always clean up so an assertion does not leave worker processes running.
  if (runtime != null) runtime.close();
  check(!abandoned, "caller abandoned an in-flight native operation");
  check(interrupted, "interrupt status lost");
  if (failing != null) check(error instanceof IllegalStateException && error.getMessage().equals("native failure"), "native error lost: " + error);
  else check(error == null, "unexpected error: " + error);
  check(live.get() == 0, "native handle leaked");
 }
}'''

JNI = '''#include <jni.h>
static jclass probe(JNIEnv *env) { return (*env)->FindClass(env, "dev/nattle/packsanity/ut/WorkerProbe"); }
JNIEXPORT jlong JNICALL Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeCreate(JNIEnv *env, jclass cls, jstring home, jstring modules) {
 jclass p = probe(env);
 return (*env)->CallStaticLongMethod(env, p, (*env)->GetStaticMethodID(env, p, "created", "()J"));
}
JNIEXPORT void JNICALL Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
 jclass p = probe(env);
 (*env)->CallStaticVoidMethod(env, p, (*env)->GetStaticMethodID(env, p, "closed", "()V"));
}
JNIEXPORT jstring JNICALL Java_dev_nattle_packsanity_ut_PythonNativeRuntime_nativeCall(JNIEnv *env, jclass cls, jlong handle, jint operation, jstring request) {
 jclass p = probe(env);
 return (*env)->CallStaticObjectMethod(env, p, (*env)->GetStaticMethodID(env, p, "called", "()Ljava/lang/String;"));
}'''


class AndroidWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        repository = Path(__file__).resolve().parents[4]
        java_home = os.environ.get('PACKSANITY_TEST_JAVA_HOME') or os.environ.get('JAVA_HOME')
        if java_home is None:
            settings = subprocess.run(['java', '-XshowSettings:properties', '-version'],
                                      capture_output=True, text=True, check=True).stderr
            java_home = next(line.split('=', 1)[1].strip() for line in settings.splitlines()
                             if 'java.home =' in line)
        java_home = Path(java_home)
        if not (java_home / 'include/jni.h').is_file():
            raise RuntimeError(f'JNI test requires a full JDK with include/jni.h: {java_home}')
        cls.java = str(java_home / 'bin/java')
        harness = cls.root / 'WorkerProbe.java'
        harness.write_text(HARNESS)
        subprocess.run([str(java_home / 'bin/javac'), '-d', str(cls.root), str(harness), str(repository /
                        'runtime-android/src/main/java/dev/nattle/packsanity/ut/PythonNativeRuntime.java')],
                       check=True)
        native = cls.root / 'worker.c'
        native.write_text(JNI)
        platform = 'darwin' if sys.platform == 'darwin' else 'linux'
        library = cls.root / ('libpacksanity_python.dylib' if platform == 'darwin' else 'libpacksanity_python.so')
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
            '-shared', '-fPIC', '-I', str(java_home / 'include'), '-I', str(java_home / 'include' / platform),
            str(native), '-o', str(library)], check=True)

    def probe(self, operation, failing=False):
        result = subprocess.run([self.java, '-Djava.library.path=' + str(self.root), '-cp', str(self.root),
                                 'dev.nattle.packsanity.ut.WorkerProbe', operation, *(['fail'] if failing else [])],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_interrupted_creation_keeps_ownership_until_handle_can_be_closed(self):
        self.probe('create')

    def test_interrupted_call_returns_its_result_and_preserves_interrupt_status(self):
        self.probe('call')

    def test_interrupted_close_waits_for_native_teardown(self):
        self.probe('close')

    def test_native_creation_error_is_not_replaced_by_interruption(self):
        self.probe('create', failing=True)

    def test_native_call_error_is_not_replaced_by_interruption(self):
        self.probe('call', failing=True)
