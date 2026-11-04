package io.github.yuan.xiaoxibyok;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.*;
import de.robv.android.xposed.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

final class FilesUi {
  static final int PICK = 48201, EXPORT = 48202, TREE = 48203;
  static final int LIMIT = 64 * 1024 * 1024;
  static Request pending;

  static final class Request {
    final Activity owner;
    Consumer<List<Uri>> selected;
    Consumer<byte[]> directory;
    byte[] bytes;
    File file;

    Request(Activity owner) {
      this.owner = owner;
    }
  }

  static final class LimitedBytes extends ByteArrayOutputStream {
    LimitedBytes() {
      super(8192);
    }

    void check(int count) throws IOException {
      if (count < 0 || size() + count > LIMIT) throw new IOException("内容超过 64 MiB，请分批处理");
    }

    public synchronized void write(int value) {
      try {
        check(1);
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      super.write(value);
    }

    public synchronized void write(byte[] buffer, int offset, int count) {
      try {
        check(count);
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      super.write(buffer, offset, count);
    }
  }

  static void install() {
    XposedBridge.hookAllMethods(
        Activity.class,
        "onActivityResult",
        new XC_MethodHook() {
          protected void beforeHookedMethod(MethodHookParam p) {
            int code = (Integer) p.args[0];
            if (code < PICK || code > TREE) return;
            p.setResult(null);
            Request request = pending;
            pending = null;
            if (request == null || request.owner != p.thisObject) return;
            Intent data = (Intent) p.args[2];
            if ((Integer) p.args[1] != Activity.RESULT_OK || data == null) return;
            try {
              if (code == EXPORT) {
                Uri uri = data.getData();
                if (uri == null) return;
                Host.work(
                    () -> {
                      try (OutputStream out =
                          Host.app().getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException("无法打开导出位置");
                        if (request.file != null)
                          java.nio.file.Files.copy(request.file.toPath(), out);
                        else if (request.bytes != null) out.write(request.bytes);
                      }
                      return "已导出";
                    },
                    result -> NativeUi.toast(result.toString()));
              } else if (code == TREE) {
                Uri uri = data.getData();
                if (uri == null || request.directory == null) return;
                Host.work(() -> zipTree(uri), result -> request.directory.accept((byte[]) result));
              } else if (request.selected != null) {
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                  for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
                if (!uris.isEmpty()) request.selected.accept(uris);
              }
            } catch (Throwable error) {
              NativeUi.error(error);
            }
          }
        });
    XposedBridge.hookAllMethods(
        Activity.class,
        "onDestroy",
        new XC_MethodHook() {
          protected void afterHookedMethod(MethodHookParam p) {
            cancel((Activity) p.thisObject);
          }
        });
  }

  static void cancel(Activity owner) {
    if (pending != null && pending.owner == owner) pending = null;
  }

  static boolean busy() {
    if (pending == null) return false;
    NativeUi.toast("请先完成或取消当前文件操作");
    return true;
  }

  static void launch(Activity activity, Intent intent, int code, Request request) {
    try {
      activity.startActivityForResult(intent, code);
    } catch (Throwable error) {
      if (pending == request) pending = null;
      NativeUi.error(error);
    }
  }

  static void pick(Activity activity, String mime, boolean multiple, Consumer<List<Uri>> callback) {
    if (busy()) return;
    Request request = new Request(activity);
    request.selected = callback;
    pending = request;
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType(mime)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
    launch(activity, intent, PICK, request);
  }

  static void read(Activity activity, Consumer<byte[]> callback) {
    pick(
        activity,
        "*/*",
        false,
        uris -> Host.work(() -> bytes(uris.get(0)), result -> callback.accept((byte[]) result)));
  }

  static byte[] bytes(Uri uri) throws IOException {
    try (InputStream in = Host.app().getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("无法读取所选文件");
      LimitedBytes out = new LimitedBytes();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      return out.toByteArray();
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
  }

  static String name(Uri uri) {
    try (Cursor cursor =
        Host.app()
            .getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (name != null && !name.trim().isEmpty())
          return name.replace('/', '_').replace('\\', '_');
      }
      return "skill.zip";
    }
  }

  static void save(Activity activity, String name, String mime, byte[] bytes) {
    if (busy()) return;
    Request request = new Request(activity);
    request.bytes = bytes;
    pending = request;
    launch(activity, exportIntent(name, mime), EXPORT, request);
  }

  static void saveFile(Activity activity, String name, String mime, File file) {
    if (busy()) return;
    Request request = new Request(activity);
    request.file = file;
    pending = request;
    launch(activity, exportIntent(name, mime), EXPORT, request);
  }

  static Intent exportIntent(String name, String mime) {
    return new Intent(Intent.ACTION_CREATE_DOCUMENT)
        .setType(mime)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(Intent.EXTRA_TITLE, name);
  }

  static void tree(Activity activity, Consumer<byte[]> callback) {
    if (busy()) return;
    Request request = new Request(activity);
    request.directory = callback;
    pending = request;
    launch(activity, new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), TREE, request);
  }

  static byte[] zipTree(Uri uri) throws IOException {
    LimitedBytes out = new LimitedBytes();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      addTree(zip, uri, DocumentsContract.getTreeDocumentId(uri), "");
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
    return out.toByteArray();
  }

  static void addTree(ZipOutputStream zip, Uri tree, String id, String prefix) throws IOException {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
    try (Cursor cursor =
        Host.app()
            .getContentResolver()
            .query(
                children,
                new String[] {"document_id", "_display_name", "mime_type"},
                null,
                null,
                null)) {
      if (cursor == null) throw new IOException("无法读取所选文件夹");
      while (cursor.moveToNext()) {
        String child = cursor.getString(0), name = prefix + cursor.getString(1);
        if (cursor.getString(2).equals(DocumentsContract.Document.MIME_TYPE_DIR))
          addTree(zip, tree, child, name + "/");
        else {
          zip.putNextEntry(new ZipEntry(entryName(name)));
          zip.write(bytes(DocumentsContract.buildDocumentUriUsingTree(tree, child)));
          zip.closeEntry();
        }
      }
    }
  }

  static byte[] zip(File root, Map<String, byte[]> overrides) throws IOException {
    LimitedBytes out = new LimitedBytes();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      if (root != null) addFiles(zip, root, root, overrides);
      for (Map.Entry<String, byte[]> entry : overrides.entrySet()) {
        if (entry.getValue() == null) continue;
        zip.putNextEntry(new ZipEntry(entryName(entry.getKey())));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    } catch (UncheckedIOException error) {
      throw error.getCause();
    }
    return out.toByteArray();
  }

  static String entryName(String path) throws IOException {
    String name = path.replace('\\', '/');
    if (name.isEmpty() || name.startsWith("/")) throw new IOException("非法文件路径");
    for (String part : name.split("/"))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException("文件路径不能超出 Skill 目录");
    return name;
  }

  static void addFiles(ZipOutputStream zip, File root, File dir, Map<String, byte[]> overrides)
      throws IOException {
    File[] files = dir.listFiles();
    if (files == null) throw new IOException("无法读取目录：" + dir.getName());
    for (File file : files) {
      String name = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
      if (name.equals(".agentos") || name.equals("node_modules") || name.equals(".git")) continue;
      if (file.isDirectory()) addFiles(zip, root, file, overrides);
      else if (!overrides.containsKey(name)) {
        zip.putNextEntry(new ZipEntry(entryName(name)));
        java.nio.file.Files.copy(file.toPath(), zip);
        zip.closeEntry();
      }
    }
  }
}
