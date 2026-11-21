// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Yicons

package org.lb2002.files;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public final class PreviewActivity extends Activity {
    private static final int MAX_TEXT = 2 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private LinearLayout controls;
    private TextView status;
    private ImageView image;
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfFile;
    private volatile long request;
    private Bitmap shown;
    private File file;
    private int page;
    private int pageCount;
    private float scale = 1;
    private int rotation;
    private final List<FileIO.Entry> pictures = new ArrayList<>();
    private int pictureIndex;

    static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    static boolean imageFile(String path) {
        return Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif").contains(extension(path));
    }
    static boolean supports(String path) {
        return imageFile(path) || Arrays.asList("pdf", "txt", "log", "md", "csv", "json", "xml",
            "ini", "conf", "yaml", "yml", "srt", "lrc", "html", "htm", "docx", "xlsx", "pptx").contains(extension(path));
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(25), dp(40), dp(20));
        root.setBackgroundColor(0xFF111821);
        getWindow().setNavigationBarColor(0xFF111821);
        TextView title = text(new File(getIntent().getStringExtra("path") == null ? "" :
            getIntent().getStringExtra("path")).getName(), 24, 0xFFE8EDF4);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(title);
        controls = new LinearLayout(this);
        root.addView(controls);
        button("返回文件夹", this::finish);
        status = text("正在打开…", 14, 0xFF97A4B5);
        status.setPadding(0, dp(6), 0, dp(8));
        root.addView(status);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(0xFF1B2531); surface.setCornerRadius(dp(16));
        content.setBackground(surface);
        content.setClipToOutline(true);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        worker.execute(() -> {
            try {
                file = FileIO.allowedFile(this, getIntent().getStringExtra("path"));
                if (imageFile(file.getPath())) {
                    pictures.addAll(FileIO.list(file.getParent()));
                    pictures.removeIf(e -> e.directory || !imageFile(e.path));
                    for (int i = 0; i < pictures.size(); i++)
                        if (pictures.get(i).path.equals(file.getPath())) pictureIndex = i;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        prepareImage();
                        button("上一张", () -> nextPicture(-1));
                        button("下一张", () -> nextPicture(1));
                        button("放大", () -> zoom(1.25f));
                        button("缩小", () -> zoom(.8f));
                        button("旋转", () -> { rotation = (rotation + 90) % 360; image.setRotation(rotation); });
                        loadPicture();
                    });
                } else if ("pdf".equals(extension(file.getName()))) {
                    pdfFile = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    pdf = new PdfRenderer(pdfFile);
                    pageCount = pdf.getPageCount();
                    if (pageCount == 0) throw new Exception("PDF 没有可显示的页面");
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        prepareImage();
                        button("上一页", () -> { if (page > 0) { page--; loadPdf(); } });
                        button("下一页", () -> { if (page + 1 < pageCount) { page++; loadPdf(); } });
                        loadPdf();
                    });
                } else {
                    String ext = extension(file.getName());
                    String body = Arrays.asList("docx", "xlsx", "pptx").contains(ext)
                        ? office(file, ext) : readText(file);
                    runOnUiThread(() -> showText(body,
                        Arrays.asList("docx", "xlsx", "pptx").contains(ext) ? "文档文本预览" : "文本预览"));
                }
            } catch (Exception e) { error(e); }
        });
    }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private void button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(15);
        view.setTextColor(0xFFE8EDF4);
        view.setAllCaps(false);
        view.setStateListAnimator(null); view.setElevation(0); view.setTranslationZ(0);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable focused = new android.graphics.drawable.GradientDrawable();
        focused.setColor(0xFF2B454A); focused.setCornerRadius(dp(12)); focused.setStroke(dp(2), 0xFF75C9BE);
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(0xFF24313F); normal.setCornerRadius(dp(12));
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        view.setBackground(states);
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(118), dp(40));
        params.setMargins(0, dp(14), dp(8), dp(6));
        controls.addView(view, params);
    }
    private void prepareImage() {
        if (isFinishing() || isDestroyed()) return;
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image, new LinearLayout.LayoutParams(-1, -1));
    }
    private void zoom(float multiplier) {
        scale = Math.max(1f, Math.min(4f, scale * multiplier));
        image.setScaleX(scale); image.setScaleY(scale);
    }
    private void nextPicture(int direction) {
        int next = pictureIndex + direction;
        if (next < 0 || next >= pictures.size()) return;
        pictureIndex = next;
        scale = 1; rotation = 0;
        image.setScaleX(1); image.setScaleY(1); image.setRotation(0);
        loadPicture();
    }
    private void loadPicture() {
        long id = ++request;
        String path = pictures.isEmpty() ? file.getPath() : pictures.get(pictureIndex).path;
        int number = pictureIndex;
        status.setText("正在读取图片…");
        worker.execute(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                if (bounds.outWidth <= 0) throw new Exception("图片格式不受当前系统支持");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 2560 ||
                       bounds.outHeight / options.inSampleSize > 1440) options.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeFile(path, options);
                if (bitmap == null) throw new Exception("图片无法解码");
                display(bitmap, id, (number + 1) + " / " + Math.max(1, pictures.size())
                    + " · " + new File(path).getName() + " · " + bounds.outWidth + " × " + bounds.outHeight);
            } catch (Exception e) { if (id == request) error(e); }
        });
    }
    private void loadPdf() {
        long id = ++request;
        int number = page;
        status.setText("正在读取第 " + (number + 1) + " 页…");
        worker.execute(() -> {
            try (PdfRenderer.Page sheet = pdf.openPage(number)) {
                float ratio = Math.min(1920f / sheet.getWidth(), 1080f / sheet.getHeight());
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (sheet.getWidth() * ratio)),
                    Math.max(1, (int) (sheet.getHeight() * ratio)), Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                sheet.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                display(bitmap, id, "第 " + (number + 1) + " / " + pageCount + " 页");
            } catch (Exception e) { if (id == request) error(e); }
        });
    }
    private void display(Bitmap bitmap, long id, String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || id != request) { bitmap.recycle(); return; }
            shown = bitmap;
            image.setImageBitmap(bitmap);
            // ImageView releases the old drawable here; ART reclaims its backing bitmap.
            status.setText(message);
        });
    }
    private void showText(String body, String type) {
        if (isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        TextView view = text(body.isEmpty() ? "文件内容为空" : body, 19, 0xFFE8EDF4);
        String ext = extension(file.getName());
        if (Arrays.asList("json", "xml", "log", "ini", "conf", "yaml", "yml").contains(ext))
            view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(28), dp(22), dp(28), dp(22));
        view.setLineSpacing(dp(7), 1);
        scroll.addView(view);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        scroll.setFocusable(true); scroll.requestFocus();
        status.setText(type);
    }
    private static String readText(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = limited(in);
            int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef
                && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
            if (bytes.length >= 2 && (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe ||
                    bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))
                return new String(bytes, StandardCharsets.UTF_16);
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            } catch (CharacterCodingException e) { return new String(bytes, Charset.forName("GB18030")); }
        }
    }
    private static byte[] limited(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > MAX_TEXT) throw new Exception("文本内容超过 2 MB，请使用专门的文档应用打开");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private static XmlPullParser xml(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new Exception("文档缺少 " + name);
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) { bytes = limited(in); }
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new java.io.ByteArrayInputStream(bytes), null);
        return parser;
    }
    private static String office(File file, String extension) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            if ("xlsx".equals(extension)) return spreadsheet(zip);
            StringBuilder result = new StringBuilder();
            List<String> parts = new ArrayList<>();
            if ("docx".equals(extension)) parts.add("word/document.xml");
            else for (int i = 1; i <= 200 && zip.getEntry("ppt/slides/slide" + i + ".xml") != null; i++)
                parts.add("ppt/slides/slide" + i + ".xml");
            for (String part : parts) {
                if ("pptx".equals(extension)) result.append("\n— 第 ").append(parts.indexOf(part) + 1).append(" 页 —\n");
                XmlPullParser parser = xml(zip, part);
                while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() == XmlPullParser.START_TAG && "t".equals(parser.getName()))
                        result.append(parser.nextText());
                    else if (parser.getEventType() == XmlPullParser.END_TAG && "p".equals(parser.getName()))
                        result.append('\n');
                    if (result.length() > MAX_TEXT) throw new Exception("文档文本过长");
                    parser.next();
                }
            }
            return result.toString();
        }
    }
    private static String spreadsheet(ZipFile zip) throws Exception {
        List<String> strings = new ArrayList<>();
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            XmlPullParser parser = xml(zip, "xl/sharedStrings.xml");
            StringBuilder item = null;
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG && "si".equals(parser.getName())) item = new StringBuilder();
                if (parser.getEventType() == XmlPullParser.START_TAG && "t".equals(parser.getName()) && item != null) item.append(parser.nextText());
                if (parser.getEventType() == XmlPullParser.END_TAG && "si".equals(parser.getName()) && item != null) { strings.add(item.toString()); item = null; }
                parser.next();
            }
        }
        StringBuilder result = new StringBuilder();
        for (int sheet = 1; sheet <= 50 && zip.getEntry("xl/worksheets/sheet" + sheet + ".xml") != null; sheet++) {
            result.append("\n— 工作表 ").append(sheet).append(" —\n");
            XmlPullParser parser = xml(zip, "xl/worksheets/sheet" + sheet + ".xml");
            String type = "";
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG && "c".equals(parser.getName())) {
                    type = parser.getAttributeValue(null, "t");
                    String address = parser.getAttributeValue(null, "r");
                    if (address != null) result.append(address).append(": ");
                }
                if (parser.getEventType() == XmlPullParser.START_TAG &&
                        ("v".equals(parser.getName()) || "t".equals(parser.getName()))) {
                    String value = parser.nextText();
                    if ("s".equals(type)) {
                        int index = Integer.parseInt(value);
                        value = index >= 0 && index < strings.size() ? strings.get(index) : value;
                    }
                    result.append(value);
                }
                if (parser.getEventType() == XmlPullParser.END_TAG && "c".equals(parser.getName())) result.append("    ");
                if (parser.getEventType() == XmlPullParser.END_TAG && "row".equals(parser.getName())) result.append('\n');
                if (result.length() > MAX_TEXT) throw new Exception("表格文本过长");
                parser.next();
            }
        }
        return result.toString();
    }
    private void error(Exception e) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) status.setText("无法预览 · " + e.getMessage());
        });
    }
    @Override public void onDestroy() {
        ++request;
        worker.execute(() -> {
            if (pdf != null) pdf.close();
            try { if (pdfFile != null) pdfFile.close(); } catch (Exception ignored) {}
        });
        worker.shutdown();
        super.onDestroy();
    }
}
