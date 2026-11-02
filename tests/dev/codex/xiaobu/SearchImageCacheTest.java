package dev.codex.xiaobu;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.Arrays;

/** Real files: unique handoffs, private placement, path rejection and expiration. */
public final class SearchImageCacheTest {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try { type = Class.forName("dev.codex.xiaobu.SearchImageCache"); }
        catch (ClassNotFoundException absent) { throw new AssertionError("Image searches need a private cache instead of a gallery write"); }
        File sandbox = Files.createTempDirectory("xiaobu-search-test").toFile();
        File cache = new File(sandbox, "cache"), gallery = new File(sandbox, "Pictures");
        gallery.mkdir();
        Object store = type.getDeclaredConstructor(File.class).newInstance(cache);
        File first = (File) type.getDeclaredMethod("create").invoke(store);
        File second = (File) type.getDeclaredMethod("create").invoke(store);
        check(!first.equals(second), "Each search must retain its own original");
        check(first.getParentFile().equals(cache), "Search belongs in private cache");
        Files.write(first.toPath(), new byte[]{1, 2, 3});
        check(Arrays.equals(Files.readAllBytes(((File) type.getDeclaredMethod("resolve", String.class)
                .invoke(store, first.getName())).toPath()), new byte[]{1, 2, 3}), "Handoff reads original bytes");
        for (String invalid : new String[]{"../Pictures/a.png", "/etc/passwd", "a/../../x", "", "unknown.png"}) {
            try { type.getDeclaredMethod("resolve", String.class).invoke(store, invalid); throw new AssertionError("Invalid cache name accepted"); }
            catch (InvocationTargetException rejected) { check(rejected.getCause() instanceof java.io.IOException, "Invalid path rejection"); }
        }
        first.setLastModified(System.currentTimeMillis() - 2 * 86400000L);
        type.getDeclaredMethod("prune").invoke(store);
        check(!first.exists() && second.exists(), "Expire old cache without deleting recent handoffs");
        check(gallery.list().length == 0, "Search never creates gallery files");
        second.delete(); cache.delete(); gallery.delete(); sandbox.delete();
        System.out.println("SearchImageCacheTest: private placement, unique originals, traversal rejection and expiry passed");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
