package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.api.QuarkApi;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.quark.ShareData;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author ColaMint & Adam & FongMi
 */
public class Quark extends Spider {


    @Override
    public void init(Context context, String extend) throws Exception {

        QuarkApi.get().setCookie(extend);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {

        ShareData shareData = QuarkApi.get().getShareData(ids.get(0));
        return Result.string(QuarkApi.get().getVod(shareData));
    }


    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return QuarkApi.get().playerContent(id.split("\\+\\+"), flag);

    }

    /**
     * 獲取詳情內容視頻播放來源（多 shared_link）
     *
     * @param ids share_link 集合
     * @param i
     * @return 詳情內容視頻播放來源
     */
    public String detailContentVodPlayFrom(List<String> ids, int index) {
        List<String> playFrom = new ArrayList<>();
       /* if (ids.size() < 2){
            return TextUtils.join("$$$",  QuarkApi.get().getPlayFormatList());
        }*/

        for (int i = 1; i <= ids.size(); i++) {
            playFrom.add(String.format("quark原画#%02d_%02d" ,i , index));
            for (String s : QuarkApi.get().getPlayFormatList()) {
                playFrom.add(String.format(Locale.getDefault(), "quark" + s + "#%02d_%02d", i, index));

            }

        }
        return TextUtils.join("$$$", playFrom);
    }

    /**
     * 獲取詳情內容視頻播放地址（多 share_link）
     *
     * @param ids share_link 集合
     * @return 詳情內容視頻播放地址
     */
    public String detailContentVodPlayUrl(List<String> ids)  {
        List<String> playUrl = new ArrayList<>();
        for (String id : ids) {
            ShareData shareData = QuarkApi.get().getShareData(id);
            try {
                playUrl.add(QuarkApi.get().getVod(shareData)==null?"":QuarkApi.get().getVod(shareData).getVodPlayUrl());
            } catch (Exception e) {
                SpiderDebug.log("获取播放地址出错:" + e.getMessage());
                playUrl.add("");
            }
        }
        return TextUtils.join("$$$", playUrl);
    }

    public static Object[] proxy(Map<String, String> params,String url ,Map<String, String> header) throws Exception {
        String type = params.get("type");
        if ("video".equals(type)) return QuarkApi.get().proxyVideo(params,url,header);
        //if ("sub".equals(type)) return AliYun.get().proxySub(params);
        return null;
    }
}
