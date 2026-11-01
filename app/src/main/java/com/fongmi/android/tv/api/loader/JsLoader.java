package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.exception.SpiderLoadException;
import com.github.catvod.crawler.Spider;
import com.github.catvod.runtime.QuickJsRuntime;
import com.github.catvod.utils.RuntimeModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import dalvik.system.DexClassLoader;

public class JsLoader {

    private final Map<String, Spider> spiders;
    private final Map<String, FutureTask<Spider>> loading;
    private final QuickJsRuntime loader;
    private final QuickJsRuntime.Session module;
    private String recent;
    private boolean closed;

    public JsLoader() {
        this(RuntimeModule.QUICKJS.entry(QuickJsRuntime.class));
    }

    JsLoader(JsLoader previous) {
        this(previous.loader);
    }

    private JsLoader(QuickJsRuntime loader) {
        spiders = new HashMap<>();
        loading = new HashMap<>();
        module = loader.session();
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
        failure = clearModule(failure);
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

    private Throwable clearModule(Throwable failure) {
        try {
            module.clear();
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

    public Spider getSpider(String key, String api, String ext, String jar) {
        FutureTask<Spider> task;
        synchronized (this) {
            checkOpen();
            Spider spider = spiders.get(key);
            if (spider != null) return spider;
            task = loading.computeIfAbsent(key, k -> new FutureTask<>(() -> createSpider(k, api, ext, jar)));
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
        if (closed) throw new CancellationException("JavaScript loader is closed");
    }

    private Spider createSpider(String key, String api, String ext, String jar) {
        Spider spider = null;
        try {
            spider = newSpider(key, api, BaseLoader.get().dex(jar));
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

    Spider newSpider(String key, String api, DexClassLoader dex) {
        Spider spider = module.spider(api, dex, JsHost.INSTANCE);
        spider.siteKey = key;
        return spider;
    }

    void cancel() {
        clear();
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
