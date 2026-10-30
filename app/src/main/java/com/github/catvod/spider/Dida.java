package com.github.catvod.spider;


import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.ProxyVideo;
import com.github.catvod.utils.Util;
import com.google.gson.JsonElement;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo for self study
 * <p>
 * Source from Author: CatVod
 */

public class Dida extends Cloud {

    private static String siteUrl = "https://www.didahd.xyz/";


    protected JSONObject filterConfig;


    //   protected Pattern t = Pattern.compile("(\\S+)");

    protected static HashMap<String, String> Headers() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36");
        headers.put("Referer", siteUrl);
        return headers;
    }

    private static String doReplaceRegex(Pattern pattern, String src) {
        if (pattern == null) return src;
        try {
            Matcher matcher = pattern.matcher(src);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return src;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context,extend);
        JsonElement json = Json.parse(extend);
        String html = OkHttp.string(json.getAsJsonObject().get("site").getAsString());
        Document doc = Jsoup.parse(html);
        for (Element element : doc.select("a")) {
            if (element.text().contains("https")) {
                siteUrl = element.attr("href");
                break;
            }
        }
        SpiderDebug.log("ddys =====>" + siteUrl); // js_debug.log

    }

    /**
     * 爬虫headers
     *
     * @param url
     * @return
     */
    protected HashMap<String, String> getHeaders(String url) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.5005.62 Safari/537.36");
        headers.put("Referer", siteUrl);
        return headers;
    }

    /**
     * 获取分类数据 + 首页最近更新视频列表数据
     *
     * @param filter 是否开启筛选 关联的是 软件设置中 首页数据源里的筛选开关
     * @return
     */
    @Override
    public String homeContent(boolean filter) {


        String url = siteUrl + '/';
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(url)));
        Elements elements = doc.select("ul.nav-menu > li > a");
        List<Class> classes = new ArrayList<>();

        for (Element ele : elements) {
            String name = ele.text();
            String id = ele.attr("href");
            if (ele.attr("href").contains("type")) {
                classes.add(new Class(id, name));
            }
        }
        return Result.string(classes, parseVodListFromDoc(doc), filterConfig);
    }

    /**
     * 获取分类信息数据
     *
     * @param tid    分类id
     * @param pg     页数
     * @param filter 同homeContent方法中的filter
     * @param extend 筛选参数{k:v, k1:v1}
     * @return
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        String url = "";

        String type = tid.replace("/type/", "").replace(".html", "");
        url = String.format("%sshow/%s--------%s---.html", siteUrl, type, pg);
        //System.out.println(url);
        String html = OkHttp.string(url, getHeaders(url));
        Document doc = Jsoup.parse(html);

        int page = Integer.parseInt(pg), limit = 48, total = Integer.MAX_VALUE;

        int count = Integer.MAX_VALUE;


        return Result.get().vod(parseVodListFromDoc(doc)).page(page, count, limit, total).string();
    }

    private List<Vod> parseVodListFromDoc(Document doc) {
        List<Vod> vods = new ArrayList<>();
        // 取首页推荐视频列表
        Elements list = doc.select("div.myui-vodlist__box");

        for (int i = 0; i < list.size(); i++) {
            Element vod = list.get(i);
            String title = vod.selectFirst("h4.title > a").text();
            String id = vod.selectFirst("h4.title > a").attr("href");
            String image = vod.selectFirst("a.myui-vodlist__thumb").attr("data-original");

            vods.add(new Vod(id, title, image));
        }
        return vods;
    }

    /**
     * 视频详情信息
     *
     * @param ids 视频id
     * @return
     */
    @Override
    public String detailContent(List<String> ids) throws ExecutionException, InterruptedException {
        Vod item = new Vod();
        // 视频详情url
        String url = ids.get(0);
        if (!url.startsWith("http")) {
            url = siteUrl + url;
        }
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(url)));
        JSONObject result = new JSONObject();
        JSONObject vodList = new JSONObject();
        //分类：剧情地区：日本语言：日语年份：2026又名：PoliticalLupin/Political鲁邦主演：石原里美高桥一生河合优实渡部笃郎草刈正雄导演：高野舞更新时间：2026-10-2919:56:04解说：
        String infos = doc.select("div.myui-content__detail p").text();
        // 取基本数据
        String cover = doc.select("div.myui-content__thumb img").attr("data-original");
        item.setVodId(url);
        item.setVodName(doc.selectFirst("div.myui-content__detail > h1").text());
        item.setVodPic(cover);
        item.setVodArea(Util.getStrByRegex(Pattern.compile("地区：(.*?)语言："), infos));
        item.setTypeName(Util.getStrByRegex(Pattern.compile("分类：(.*?)地区："), infos));
        item.setVodYear(Util.getStrByRegex(Pattern.compile("年份：(.*?)又名："), infos));
        item.setVodDirector(Util.getStrByRegex(Pattern.compile("导演：(.*?)更新时间："), infos));
        item.setVodActor(Util.getStrByRegex(Pattern.compile("主演：(.*?)导演："), infos));
        item.setVodRemarks(doc.select("div.myui-panel  p").text());

        Elements tabs = doc.select("div.myui-panel_hd  ul.nav-tabs li a");

       // Vod.VodPlayBuilder builder = new Vod.VodPlayBuilder();
//普通解析
       /* for (int k = 0; k < tabs.size(); k++) {
            String sourceName = tabs.get(k).text();
            if (!sourceName.contains("网盘")) {
                Elements vods = doc.select("#playlist" + k).select("ul li a");
                for (Element vod : vods) {
                    List<Vod.VodPlayBuilder.PlayUrl> list = new ArrayList<>();
                    Vod.VodPlayBuilder.PlayUrl playUrl = new Vod.VodPlayBuilder.PlayUrl();
                    playUrl.name = vod.text();
                    playUrl.url = vod.attr("href");
                    list.add(playUrl);
                    builder.append(sourceName, list);
                }
            }
        }*/
//网盘
        List<String> shareLinks = new ArrayList<>();
        Elements clouds = doc.select("div.myui-panel_bd  a");
        for (Element cloud : clouds) {
            shareLinks.add(cloud.text());
        }

        String quarkNames = "";
        String quarkUrls = "";
        if (!shareLinks.isEmpty()) {
            quarkUrls = super.detailContentVodPlayUrl(shareLinks);
            quarkNames = super.detailContentVodPlayFrom(shareLinks);

        }

        //Vod.VodPlayBuilder.BuildResult buildResult = builder.build();

        item.setVodPlayFrom( quarkNames);
        item.setVodPlayUrl( quarkUrls);


        return Result.string(item);


    }



    /**
     * 获取视频播放信息
     *
     * @param flag     播放源
     * @param id       视频id
     * @param vipFlags 所有可能需要vip解析的源
     * @return
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (flag.contains("quark") || flag.contains("uc")||flag.contains("BD")) {
            return super.playerContent(flag, id, vipFlags);
        } else {
            String content = OkHttp.string(siteUrl+id, getHeaders(siteUrl));

            Matcher matcher = Pattern.compile("player_aaaa=(.*?)</script>").matcher(content);
            String json = matcher.find() ? matcher.group(1) : "";
            JSONObject player = new JSONObject(json);
            String url = player.getString("url");
            int encrypt = player.getInt("encrypt");
            String urlNext = player.getString("url_next");
            String from = player.getString("from");
            if(encrypt==3){
                String urlTmp="static/player/artplayer/?url="+url+"&next="+ (StringUtils.isBlank(urlNext) ?"":siteUrl+urlNext);
                String playerInfo=OkHttp.string(siteUrl+urlTmp, getHeaders(siteUrl));

            }



            return Result.get().url(ProxyVideo.buildCommonProxyUrl(id, Util.webHeaders(siteUrl))).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws UnsupportedEncodingException {

        String url = siteUrl + "?search/-------------.html?wd=" + java.net.URLEncoder.encode(key, "UTF-8") + "&submit=";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(url)));
        List<Vod> vods = new ArrayList<>();
        Elements elements = doc.select(" a.myui-vodlist__thumb");
        for (int i = 0; i < elements.size(); i++) {
            String id = elements.get(i).attr("href");
            String name = elements.get(i).attr("title");
            String pic =  elements.get(i).select("img").attr("src");
            vods.add(new Vod(id, name, pic));
        }
        return Result.string(vods);
    }


}
