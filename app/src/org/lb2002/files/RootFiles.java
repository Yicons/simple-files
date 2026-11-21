// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Yicons
package org.lb2002.files;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** A short-lived, read-only root process. Files are passed as seekable FDs. */
public final class RootFiles {
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static final class Session implements AutoCloseable {
        final LocalSocket socket;
        final DataInputStream input;
        Session(Context context, String action, String path) throws IOException {
            String[] roots = FileIO.protectedRoots(context, path);
            if (roots == null) throw new IOException("不是允许访问的受限存储目录");
            String address = "simple-files-" + UUID.randomUUID();
            LocalServerSocket server = new LocalServerSocket(address);
            ExecutorService threads = Executors.newSingleThreadExecutor();
            AtomicReference<Exception> failure = new AtomicReference<>();
            try {
                android.system.Os.setsockoptTimeval(server.getFileDescriptor(), android.system.OsConstants.SOL_SOCKET,
                    android.system.OsConstants.SO_RCVTIMEO, android.system.StructTimeval.fromMillis(1000));
            } catch (android.system.ErrnoException e) { server.close(); threads.shutdown(); throw new IOException(e); }
            String command = "CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process /system/bin org.lb2002.files.RootFiles "
                + quote(address) + " " + quote(action) + " " + quote(roots[0]) + " "
                + quote(roots[1]) + " " + quote(new File(path).toPath().normalize().toString());
            threads.submit(() -> {
                try { org.lb2002.shared.RootAccess.run(context, command); }
                catch (Exception e) { failure.set(e); }
                finally { try { server.close(); } catch (IOException ignored) {} }
            });
            LocalSocket accepted = null;
            try {
                long deadline = android.os.SystemClock.elapsedRealtime() + 40000;
                while (accepted == null) {
                    if (failure.get() != null) throw failure.get();
                    try { accepted = server.accept(); }
                    catch (IOException waiting) {
                        if (failure.get() != null) throw failure.get();
                        if (android.os.SystemClock.elapsedRealtime() >= deadline)
                            throw new IOException("Root 授权未完成或读取进程未响应，请检查超级用户授权", waiting);
                    }
                }
                accepted.setSoTimeout(30000);
                if (accepted.getPeerCredentials().getUid() != 0) throw new IOException("Root 连接身份不正确");
                DataInputStream stream = new DataInputStream(accepted.getInputStream());
                if (stream.readUnsignedByte() != 0) throw new IOException(stream.readUTF());
                socket = accepted; input = stream;
            } catch (Exception e) {
                if (accepted != null) try { accepted.close(); } catch (IOException ignored) {}
                Exception cause = failure.get();
                throw new IOException(cause != null ? cause.getMessage() : "Root 读取失败：" + e.getMessage(), e);
            } finally {
                try { server.close(); } catch (IOException ignored) {}
                threads.shutdown();
            }
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    static List<FileIO.Entry> list(Context context, String path) throws IOException {
        try (Session session = new Session(context, "list", path)) {
            int count = session.input.readInt();
            if (count < 0 || count > 100000) throw new IOException("目录项目数量不受支持");
            List<FileIO.Entry> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String name = session.input.readUTF();
                boolean directory = session.input.readBoolean();
                long size = session.input.readLong();
                if (name.equals(".") || name.equals("..") || name.contains("/")) throw new IOException("目录项目无效");
                result.add(new FileIO.Entry(new File(path, name).getPath(), name, directory, size));
            }
            return result;
        }
    }

    static ParcelFileDescriptor open(Context context, String path) throws IOException {
        try (Session session = new Session(context, "open", path)) {
            FileDescriptor[] descriptors = session.socket.getAncillaryFileDescriptors();
            if (descriptors == null || descriptors.length != 1) throw new IOException("未收到文件读取句柄");
            try { return ParcelFileDescriptor.dup(descriptors[0]); }
            finally {
                try { android.system.Os.close(descriptors[0]); } catch (android.system.ErrnoException ignored) {}
            }
        }
    }

    // Only the su-launched process invokes this entry point; no exported Activity/Service.
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Invalid root reader arguments");
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(args[0], LocalSocketAddress.Namespace.ABSTRACT));
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            try {
                File logical = new File(args[2]).getAbsoluteFile();
                File requested = new File(args[4]).getAbsoluteFile();
                if (!requested.toPath().startsWith(logical.toPath())) throw new IOException("路径越界");
                String relative = logical.toPath().relativize(requested.toPath()).toString();
                File backend = new File(args[3]).getCanonicalFile();
                File target = new File(backend, relative).getCanonicalFile();
                if (!target.toPath().startsWith(backend.toPath())) throw new IOException("链接指向允许目录之外");
                if (args[1].equals("list")) {
                    File[] children = target.listFiles();
                    if (children == null) throw new IOException("目录不存在或 Root 仍无权读取");
                    if (children.length > 100000) throw new IOException("目录项目过多");
                    output.writeByte(0); output.writeInt(children.length);
                    for (File child : children) {
                        output.writeUTF(child.getName()); output.writeBoolean(child.isDirectory()); output.writeLong(child.length());
                    }
                } else if (args[1].equals("open")) {
                    if (!target.isFile()) throw new IOException("文件不存在或不是普通文件");
                    try (FileInputStream file = new FileInputStream(target)) {
                        socket.setFileDescriptorsForSend(new FileDescriptor[]{file.getFD()});
                        output.writeByte(0); output.flush(); socket.setFileDescriptorsForSend(null);
                    }
                } else throw new IOException("不支持的 Root 读取操作");
            } catch (IOException e) {
                output.writeByte(1); output.writeUTF(String.valueOf(e.getMessage()));
            }
            output.flush();
        }
    }
}
