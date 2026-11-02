package dev.codex.xiaobu;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Private originals survive asynchronous handoff; old files expire on the next search. */
final class SearchImageCache {
    private final File directory;
    SearchImageCache(File directory) { this.directory = directory; }
    synchronized File create() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Search cache unavailable");
        prune();
        return File.createTempFile("search-", ".png", directory);
    }
    File resolve(String name) throws IOException {
        if (name == null || !name.matches("search-[A-Za-z0-9_-]+\\.png")) throw new FileNotFoundException("Invalid search image");
        File file = new File(directory, name);
        if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) || !file.isFile())
            throw new FileNotFoundException("Search image expired");
        return file;
    }
    synchronized void prune() {
        File[] files = directory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 86400000L;
        for (File file : files)
            if (file.getName().matches("search-[A-Za-z0-9_-]+\\.png") && file.lastModified() < cutoff) file.delete();
    }
}
