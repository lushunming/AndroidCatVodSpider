package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.CacheBuilder;
import com.github.catvod.utils.ConcurrentLruCache;
import com.github.catvod.utils.ProxyVideo;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Proxy extends Spider {

    private static int port = -1;
    public static ConcurrentLruCache<String, String> urlMap = CacheBuilder.<String, String>newBuilder().initialCapacity(100).concurrencyLevel(Runtime.getRuntime().availableProcessors()).maximumSize(5000).recordStats().build();

    public static ConcurrentLruCache<String, Map<String, String>> headerMap = CacheBuilder.<String, Map<String, String>>newBuilder().initialCapacity(100).concurrencyLevel(Runtime.getRuntime().availableProcessors()).maximumSize(5000).recordStats().build();
    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "ck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "ali":
                return Ali.proxy(params);
            case "quark":
                String key = params.get("key");
                String url = urlMap.get(key);
                Map<String, String> header = headerMap.get(key);
                return Quark.proxy(params, url, header);
            case "uc":
                return UC.proxy(params);
            case "bili":
                return Bili.proxy(params);
            case "webdav":
                return WebDAV.vod(params);
            case "local":
                return Local.proxy(params);
            case "proxy":
                return commonProxy(params);
            default:
                return null;
        }
    }

    private static final List<String> keys = Arrays.asList("url", "header", "do", "Content-Type", "User-Agent", "Host");

    private static Object[] commonProxy(Map<String, String> params) throws Exception {
        String url = Util.base64Decode(params.get("url"));
        Map<String, String> header = new Gson().fromJson(Util.base64Decode(params.get("header")), Map.class);
        if (header == null) header = new HashMap<>();
        List<String> keys = Arrays.asList("range", "connection", "accept-encoding");
        for (String key : params.keySet()) {
            if (keys.contains(key.toLowerCase())) {
                header.put(key, params.get(key));
            }
        }
        /*for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!keys.contains(entry.getKey())) header.put(entry.getKey(), entry.getValue());
        }*/
        return ProxyVideo.proxyMultiThread(url, header);
    }


    static void adjustPort() {
        if (Proxy.port > 0) return;
        int port = 9978;
        while (port < 10000) {
            String resp = OkHttp.string("http://127.0.0.1:" + port + "/proxy?do=ck", null);
            if (resp.equals("ok")) {
                SpiderDebug.log("Found local server port " + port);
                Proxy.port = port;
                break;
            }
            port++;
        }
    }

    public static int getPort() {
        adjustPort();
        return port;
    }

    public static String getUrl() {
        adjustPort();
        return "http://127.0.0.1:" + port + "/proxy";
    }


    /**
     * 构建代理链接
     *
     * @param name   代理do参数
     * @param url    链接
     * @param header header
     * @param type   video or sub  视频还是字幕
     * @return
     */
    public static String buildProxyUrl(String name, String url, Map<String, String> header, String type) {

        String key = Util.MD5(url);
        urlMap.put(key, url);
        headerMap.put(key, header);

        return getUrl() + "?do=" + name + "&key=" + key + "&type=" + type;
    }
}
