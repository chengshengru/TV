package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.exception.SpiderLoadException;
import com.github.catvod.crawler.Spider;
import com.github.catvod.runtime.PythonRuntime;
import com.github.catvod.utils.RuntimeModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class PyLoader {

    private final Map<String, Spider> spiders;
    private final Map<String, FutureTask<Spider>> loading;
    private final PythonRuntime loader;
    private String recent;
    private boolean closed;

    public PyLoader() {
        this(RuntimeModule.PYTHON.entry(PythonRuntime.class));
    }

    PyLoader(PyLoader previous) {
        this(previous.loader);
    }

    private PyLoader(PythonRuntime loader) {
        loader.initialize();
        spiders = new HashMap<>();
        loading = new HashMap<>();
        this.loader = loader;
    }

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
            recent = null;
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

    public synchronized void setRecent(String recent) {
        checkOpen();
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        FutureTask<Spider> task;
        synchronized (this) {
            checkOpen();
            Spider spider = spiders.get(key);
            if (spider != null) return spider;
            task = loading.computeIfAbsent(key, k -> new FutureTask<>(() -> createSpider(k, api, ext)));
        }
        try {
            task.run();
            return awaitSpider(task);
        } finally {
            synchronized (this) {
                if (task.isDone()) loading.remove(key, task);
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
        if (closed) throw new CancellationException("Python loader is closed");
    }

    private Spider createSpider(String key, String api, String ext) {
        Spider spider = null;
        try {
            spider = newSpider(key, api);
            checkOpen();
            spider.init(App.get(), ext);
            cacheSpider(key, spider);
            return spider;
        } catch (Exception | Error error) {
            if (spider != null) destroySpider(spider, error);
            if (error instanceof Error fatal) throw fatal;
            throw new SpiderLoadException(error);
        }
    }

    private synchronized void cacheSpider(String key, Spider spider) {
        checkOpen();
        spiders.put(key, spider);
    }

    Spider newSpider(String key, String api) {
        return loader.spider(api, key, JsHost.INSTANCE, JsHost.INSTANCE::getUserAgent);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        Spider spider;
        synchronized (this) {
            checkOpen();
            spider = spiders.get(recent);
        }
        return spider != null ? spider.proxy(params) : null;
    }
}
