package com.winlator.cmod.roblox;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.winlator.cmod.container.Container;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vinegar-style Roblox Studio deployment installer.
 *
 * Roblox binaries are fetched at runtime from Roblox's deployment service and
 * are never bundled with or redistributed by the APK.
 */
public final class RobloxStudioInstaller {
    public interface Listener {
        void onProgress(String message, int percent);
        void onComplete(Result result);
    }

    public static final class Result {
        public final boolean success;
        public final boolean cancelled;
        public final String version;
        public final File executable;
        public final String error;

        private Result(boolean success, boolean cancelled, String version, File executable, String error) {
            this.success = success;
            this.cancelled = cancelled;
            this.version = version;
            this.executable = executable;
            this.error = error;
        }

        static Result success(String version, File executable) {
            return new Result(true, false, version, executable, null);
        }

        static Result error(String error) {
            return new Result(false, false, null, null, error);
        }

        static Result cancelledResult() {
            return new Result(false, true, null, null, null);
        }
    }

    private static final String CLIENT_VERSION_URL =
            "https://clientsettingscdn.roblox.com/v2/client-version/WindowsStudio64/channel/LIVE";
    private static final String MIRROR = "https://setup.rbxcdn.com/channel/common/";
    private static final String APP_SETTINGS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
            "<Settings>\r\n" +
            "        <ContentFolder>content</ContentFolder>\r\n" +
            "        <BaseUrl>http://www.roblox.com</BaseUrl>\r\n" +
            "</Settings>\r\n";

    private final Activity activity;
    private final Container container;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public RobloxStudioInstaller(Activity activity, Container container, Listener listener) {
        this.activity = activity;
        this.container = container;
        this.listener = listener;
    }

    public void start() {
        executor.execute(() -> {
            Result result;
            try {
                result = install();
            } catch (CancelledException e) {
                result = Result.cancelledResult();
            } catch (Exception e) {
                result = Result.error(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                executor.shutdown();
            }
            Result finalResult = result;
            mainHandler.post(() -> listener.onComplete(finalResult));
        });
    }

    public void cancel() {
        cancelled.set(true);
    }

    private Result install() throws Exception {
        publish("Checking Roblox Studio version…", 0);
        JSONObject versionJson = new JSONObject(downloadText(CLIENT_VERSION_URL));
        String version = versionJson.getString("clientVersionUpload");
        if (!version.startsWith("version-")) throw new IOException("Roblox returned an invalid deployment version");

        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        File versionsDir = new File(driveC, "Roblox/Versions");
        File installDir = new File(versionsDir, version);
        File executable = new File(installDir, "RobloxStudioBeta.exe");
        if (isPeExecutable(executable)) {
            writeCurrentVersion(driveC, version);
            publish("Roblox Studio is up to date", 100);
            return Result.success(version, executable);
        }

        File workDir = new File(activity.getCacheDir(), "roblox-studio/" + version);
        if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("Unable to create download cache");

        publish("Fetching package list…", 1);
        List<PackageInfo> packages = parseManifest(downloadText(packageUrl(version, "rbxPkgManifest.txt")));
        Map<String, String> directories = fetchPackageDirectories(version, workDir);
        long totalBytes = 0;
        for (PackageInfo pkg : packages) {
            if (!"RobloxStudioInstaller.exe".equals(pkg.name)) totalBytes += pkg.zipSize;
        }
        final long downloadTotalBytes = totalBytes;

        File stagingDir = new File(versionsDir, version + ".installing");
        deleteRecursively(stagingDir);
        if (!stagingDir.mkdirs()) throw new IOException("Unable to create Studio installation directory");

        packages.sort(Comparator.comparingLong(pkg -> pkg.zipSize));
        long completedBytes = 0;
        try {
            for (PackageInfo pkg : packages) {
                checkCancelled();
                if ("RobloxStudioInstaller.exe".equals(pkg.name)) continue;
                String relativeDestination = directories.get(pkg.name);
                if (relativeDestination == null)
                    throw new IOException("No destination for package " + pkg.name);

                File cached = new File(workDir, pkg.checksum + ".pkg");
                if (!verifyMd5(cached, pkg.checksum)) {
                    if (cached.exists() && !cached.delete()) throw new IOException("Unable to replace corrupt cache file");
                    long base = completedBytes;
                    publish("Downloading " + pkg.name, percent(base, totalBytes));
                    downloadFile(packageUrl(version, pkg.name), cached, bytes ->
                            publish("Downloading " + pkg.name, percent(base + bytes, downloadTotalBytes)));
                    if (!verifyMd5(cached, pkg.checksum)) {
                        cached.delete();
                        throw new IOException("Checksum mismatch for " + pkg.name);
                    }
                }

                publish("Installing " + pkg.name, percent(completedBytes, totalBytes));
                extractPackage(cached, new File(stagingDir, relativeDestination));
                completedBytes += pkg.zipSize;
            }

            writeText(new File(stagingDir, "AppSettings.xml"), APP_SETTINGS);
            if (!isPeExecutable(new File(stagingDir, "RobloxStudioBeta.exe")))
                throw new IOException("Deployment did not contain RobloxStudioBeta.exe");

            deleteRecursively(installDir);
            if (!stagingDir.renameTo(installDir)) throw new IOException("Unable to finalize Studio installation");
            removeOtherDeployments(versionsDir, version);
            writeCurrentVersion(driveC, version);
        } catch (Exception e) {
            deleteRecursively(stagingDir);
            throw e;
        }

        publish("Roblox Studio installed", 100);
        return Result.success(version, executable);
    }

    private Map<String, String> fetchPackageDirectories(String version, File workDir) throws Exception {
        publish("Fetching installation directives…", 1);
        File installer = new File(workDir, "RobloxStudioInstaller.exe");
        if (!isPeExecutable(installer)) downloadFile(packageUrl(version, "RobloxStudioInstaller.exe"), installer, null);
        byte[] bytes = readAllBytes(installer);
        String marker = "{\"ApplicationConfig.zip\"";
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        int start = indexOf(bytes, markerBytes, 0);
        if (start < 0) throw new IOException("Unable to find package directory map in Roblox installer");
        int end = start;
        while (end < bytes.length && bytes[end] != 0) end++;
        if (end == bytes.length) throw new IOException("Invalid package directory map in Roblox installer");

        JSONObject json = new JSONObject(new String(bytes, start, end - start, StandardCharsets.UTF_8));
        Map<String, String> result = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = json.getString(key).replace('\\', '/');
            while (value.startsWith("/")) value = value.substring(1);
            if (value.contains("../")) throw new IOException("Unsafe package destination in Roblox installer");
            result.put(key, value);
        }
        return result;
    }

    private List<PackageInfo> parseManifest(String manifest) throws IOException {
        String[] lines = manifest.replace("\r", "").split("\n");
        if (lines.length < 5 || !"v0".equals(lines[0]) || (lines.length - 1) % 4 != 0)
            throw new IOException("Unsupported Roblox package manifest");
        List<PackageInfo> result = new ArrayList<>();
        for (int i = 1; i + 3 < lines.length; i += 4) {
            try {
                result.add(new PackageInfo(lines[i], lines[i + 1],
                        Long.parseLong(lines[i + 2]), Long.parseLong(lines[i + 3])));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid package size in Roblox manifest", e);
            }
        }
        return result;
    }

    private String downloadText(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            return new String(readAllBytes(input), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private interface ByteProgress { void update(long bytes); }

    private void downloadFile(String url, File destination, ByteProgress progress) throws Exception {
        checkCancelled();
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Unable to create download directory");
        File partial = new File(destination.getPath() + ".part");
        HttpURLConnection connection = open(url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[128 * 1024];
            long copied = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled();
                output.write(buffer, 0, count);
                copied += count;
                if (progress != null) progress.update(copied);
            }
            output.getFD().sync();
        } catch (Exception e) {
            partial.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        if (destination.exists() && !destination.delete()) throw new IOException("Unable to replace cached package");
        if (!partial.renameTo(destination)) throw new IOException("Unable to finalize package download");
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Winlator-RobloxStudio/1.0");
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Roblox download failed (HTTP " + status + ")");
        }
        return connection;
    }

    private void extractPackage(File archive, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Unable to create package directory");
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled();
                String name = entry.getName().replace('\\', '/');
                File outputFile = new File(destination, name);
                String outputPath = outputFile.getCanonicalPath();
                if (!outputPath.equals(destination.getCanonicalPath()) && !outputPath.startsWith(root))
                    throw new IOException("Unsafe path in package " + archive.getName());
                if (entry.isDirectory()) {
                    if (!outputFile.isDirectory() && !outputFile.mkdirs()) throw new IOException("Unable to create package directory");
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                        throw new IOException("Unable to create package directory");
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        byte[] buffer = new byte[128 * 1024];
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            checkCancelled();
                            output.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private void writeCurrentVersion(File driveC, String version) throws IOException {
        writeText(new File(driveC, "Roblox/current-version.txt"), version + "\n");
    }

    private void removeOtherDeployments(File versionsDir, String currentVersion) {
        File[] files = versionsDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.getName().equals(currentVersion) && !file.getName().equals(currentVersion + ".installing"))
                deleteRecursively(file);
        }
    }

    private void publish(String message, int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        mainHandler.post(() -> listener.onProgress(message, safePercent));
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.get()) throw new CancelledException();
    }

    private static int percent(long value, long total) {
        if (total <= 0) return 0;
        return 2 + (int) Math.min(96, (value * 96) / total);
    }

    private static String packageUrl(String version, String name) {
        return MIRROR + version + "-" + name;
    }

    private static boolean verifyMd5(File file, String expected) throws Exception {
        if (!file.isFile()) return false;
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return expected.equalsIgnoreCase(actual.toString());
    }

    private static boolean isPeExecutable(File file) {
        if (!file.isFile() || file.length() < 64) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'M' && input.read() == 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readAllBytes(input);
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static int indexOf(byte[] source, byte[] target, int from) {
        outer: for (int i = from; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (source[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static void writeText(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Unable to create directory");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class PackageInfo {
        final String name;
        final String checksum;
        final long zipSize;
        final long size;

        PackageInfo(String name, String checksum, long zipSize, long size) {
            this.name = name;
            this.checksum = checksum;
            this.zipSize = zipSize;
            this.size = size;
        }
    }

    private static final class CancelledException extends Exception {}
}
