package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.api.node.NodeRuntime;
import com.fongmi.android.tv.exception.SpiderLoadException;
import com.github.catvod.crawler.Spider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class NodeLoader {

    private final Map<String, Spider> spiders = new HashMap<>();
    private final Map<String, FutureTask<Spider>> loading = new HashMap<>();
    private boolean closed;

    public void clear() {
        List<FutureTask<Spider>> pending;
        List<Spider> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(loading.values());
            active = new ArrayList<>(spiders.values());
            loading.clear();
            spiders.clear();
        }
        for (FutureTask<Spider> task : pending) task.cancel(true);
        Throwable failure = destroySpiders(active);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private Throwable destroySpiders(List<Spider> active) {
        Throwable failure = null;
        for (Spider spider : active) failure = destroySpider(spider, failure);
        return failure;
    }

    private Throwable destroySpider(Spider spider, Throwable failure) {
        try {
            spider.destroy();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        return failure;
    }

    public Spider getSpider(String key, String api, String ext) {
        String spKey = key + '\u0000' + api;
        FutureTask<Spider> task;
        synchronized (this) {
            checkOpen();
            Spider spider = spiders.get(spKey);
            if (spider != null) return spider;
            task = loading.computeIfAbsent(spKey, k -> new FutureTask<>(() -> createSpider(key, api, ext)));
        }
        try {
            task.run();
            return awaitSpider(task);
        } finally {
            synchronized (this) {
                if (task.isDone()) loading.remove(spKey, task);
            }
        }
    }

    private Spider awaitSpider(FutureTask<Spider> task) {
        try {
            Spider spider = task.get();
            checkOpen();
            return spider;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SpiderLoadException(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw (RuntimeException) error.getCause();
        }
    }

    private synchronized void checkOpen() {
        if (closed) throw new CancellationException("Node loader is closed");
    }

    private Spider createSpider(String key, String api, String ext) {
        Spider spider = null;
        try {
            spider = newSpider(key, api);
            checkOpen();
            spider.init(null, ext);
            cacheSpider(key, api, spider);
            return spider;
        } catch (Exception | Error error) {
            if (spider != null) destroySpider(spider, error);
            if (error instanceof Error fatal) throw fatal;
            throw new SpiderLoadException(error);
        }
    }

    private synchronized void cacheSpider(String key, String api, Spider spider) {
        checkOpen();
        spiders.put(key + '\u0000' + api, spider);
    }

    Spider newSpider(String key, String api) {
        Spider spider = NodeRuntime.get().client().spider(api);
        spider.siteKey = key;
        return spider;
    }
}
