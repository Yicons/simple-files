// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Yicons

package org.lb2002.files;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.webkit.MimeTypeMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class FileIO {
    static final class Entry {
        final String path;
        final String name;
        final boolean directory;
        final long size;
        long total;
        long free;
        Entry(String path, String name, boolean directory, long size) {
            this.path = path; this.name = name; this.directory = directory; this.size = size;
        }
    }
    private static Entry storage(String path, String label) {
        Entry entry = new Entry(path, label, true, 0);
        try {
            android.os.StatFs space = new android.os.StatFs(path);
            entry.total = space.getTotalBytes();
            entry.free = space.getAvailableBytes();
        } catch (RuntimeException ignored) {}
        return entry;
    }

    static List<Entry> roots(Context context) throws IOException {
        LinkedHashMap<String, Entry> roots = new LinkedHashMap<>();
        StorageManager manager = context.getSystemService(StorageManager.class);
        for (StorageVolume volume : manager.getStorageVolumes()) {
            if (volume.isPrimary() || !Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
            File directory = android.os.Build.VERSION.SDK_INT >= 30 ? volume.getDirectory()
                : volume.getUuid() == null ? null : new File("/storage", volume.getUuid());
            if (directory != null) {
                String path = directory.getAbsolutePath();
                roots.put(path, storage(path, "U 盘 · " + volume.getDescription(context)));
            }
        }
        // Some TV firmware omits USB volumes from StorageManager. The live mount table
        // still exposes their actual UUIDs, even when listing /storage is denied.
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length < 2) continue;
                String mounted = fields[1];
                String prefix = mounted.startsWith("/mnt/media_rw/")
                    ? "/mnt/media_rw/" : mounted.startsWith("/storage/") ? "/storage/" : "";
                if (prefix.isEmpty()) continue;
                String id = mounted.substring(prefix.length());
                if (!id.matches("(?i)([0-9a-f]{4}-[0-9a-f]{4}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9a-f]{16})")) continue;
                String path = "/storage/" + id;
                if (!roots.containsKey(path)) roots.put(path, storage(path, "U 盘 · " + id));
            }
        } catch (IOException | SecurityException inaccessible) {
            // Public StorageManager results and internal storage remain usable
            // when a manufacturer blocks access to /proc/mounts.
        }
        List<Entry> result = new ArrayList<>(roots.values());
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        result.add(storage(internal, "内部存储"));
        return result;
    }

    static List<Entry> list(String directory) throws IOException {
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(directory))) {
            for (Path path : stream) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    result.add(new Entry(path.toString(), path.getFileName().toString(),
                        attrs.isDirectory(), attrs.size()));
                } catch (IOException ignored) {
                    // A removed/unreadable entry must not hide the rest of the directory.
                }
            }
        }
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> a.directory != b.directory
            ? (a.directory ? -1 : 1) : collator.compare(a.name, b.name));
        return result;
    }

    static String mime(String path) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(
            android.net.Uri.fromFile(new File(path)).toString());
        if ("apk".equalsIgnoreCase(extension)) return "application/vnd.android.package-archive";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            extension.toLowerCase(java.util.Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    static String volumeId(Context context, String root) throws Exception {
        String id = new File(root).getName();
        if (!root.equals("/storage/" + id)) throw new IOException("只能弹出 U 盘");
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length >= 2 && fields[1].equals("/mnt/media_rw/" + id)
                        && fields[0].matches("/dev/block/vold/public:[0-9]+,[0-9]+"))
                    return fields[0].substring("/dev/block/vold/".length());
            }
        } catch (IOException | SecurityException inaccessible) { /* Try the Root storage service below. */ }
        String volumes = org.lb2002.shared.RootAccess.run(context, "sm list-volumes all");
        for (String line : volumes.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 3 && fields[0].matches("public:[0-9]+,[0-9]+")
                    && fields[1].equals("mounted") && fields[2].equalsIgnoreCase(id)) return fields[0];
        }
        throw new IOException("U 盘已移除或不再挂载");
    }

    static void eject(Context context, String root) throws Exception {
        String id = volumeId(context, root);
        String disk = diskPath(context, id);
        List<String> volumes = new ArrayList<>();
        for (Entry entry : roots(context)) {
            if (entry.path.startsWith("/storage/emulated")) continue;
            String candidate = volumeId(context, entry.path);
            if (disk.equals(diskPath(context, candidate)) && !volumes.contains(candidate)) volumes.add(candidate);
        }
        for (String volume : volumes) unmount(context, volume);
        for (int i = 0; i < 30; i++) {
            boolean mounted = false;
            for (Entry entry : roots(context)) {
                if (entry.path.startsWith("/storage/emulated")) continue;
                try { if (disk.equals(diskPath(context, volumeId(context, entry.path)))) mounted = true; }
                catch (IOException removed) { /* It finished unmounting between queries. */ }
            }
            if (!mounted) return;
            Thread.sleep(200);
        }
        throw new IOException("系统尚未完成卸载，请稍后重试");
    }

    private static String diskPath(Context context, String volume) throws Exception {
        if (!volume.matches("public:[0-9]+,[0-9]+")) throw new IOException("无效的存储卷标识");
        String path = "/sys/dev/block/" + volume.substring("public:".length()).replace(',', ':');
        String device;
        try { device = Paths.get(path).toRealPath().toString(); }
        catch (IOException | SecurityException inaccessible) {
            device = org.lb2002.shared.RootAccess.run(context, "readlink -f " + path);
        }
        int index = device.indexOf("/block/");
        if (index < 0) throw new IOException("无法确定 U 盘设备");
        int end = device.indexOf('/', index + "/block/".length());
        return end < 0 ? device : device.substring(0, end);
    }

    private static void unmount(Context context, String id) throws Exception {
        if (!id.matches("public:[0-9]+,[0-9]+")) throw new IOException("无效的存储卷标识");
        if (context.checkSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                StorageManager.class.getMethod("unmount", String.class)
                    .invoke(context.getSystemService(StorageManager.class), id);
                return;
            } catch (ReflectiveOperationException | SecurityException blocked) {
                // Some firmware restricts this API even for preinstalled apps.
            }
        }
        org.lb2002.shared.RootAccess.run(context, "sm unmount " + id);
    }

    static File allowedFile(Context context, String path) throws IOException {
        if (path == null) throw new IOException("缺少文件路径");
        Path file = Paths.get(path).toRealPath();
        for (Entry root : roots(context)) {
            try {
                Path allowed = Paths.get(root.path).toRealPath();
                if (file.startsWith(allowed) && !file.equals(allowed)) return file.toFile();
            } catch (IOException removed) { /* A different volume may have been unplugged. */ }
        }
        throw new IOException("文件不在允许访问的存储空间中");
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }
}
