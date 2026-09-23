package com.github.catvod.api

import com.github.catvod.bean.Result
import com.github.catvod.bean.Vod
import com.github.catvod.bean.Vod.VodPlayBuilder
import com.github.catvod.crawler.SpiderDebug
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Json
import com.github.catvod.utils.Launcher
import com.github.catvod.utils.Notify
import com.github.catvod.utils.ProxyServer
import com.github.catvod.utils.ProxyServerIns
import com.github.catvod.utils.Util
import com.github.catvod.utils.Util.MEDIA
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.*

object BaiduDrive {
    private val cache = mutableMapOf<String, String>();

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; SM-X800) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.40 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to "https://pan.baidu.com",
        "Referer" to "https://pan.baidu.com/"
    )

    //是否删除过文件标志
    private var deleteTag = 0;
    private val saveDirName = "TVBOX_BD"

    private var cookies = BaiDuYunHandler.get().token


    private val apiHost = "https://pan.baidu.com"
    private val displayName = listOf("BD原画")


    fun setCookie(extend: String) {
        if (extend.isEmpty()) return
        cookies = extend
    }

    fun processShareLinks(urls: List<String>): Pair<List<String>, List<String>> {

        //首先确保cookie不为空
        if (cookies.isEmpty()) {
            BaiDuYunHandler.get().startScan()
            cookies = BaiDuYunHandler.get().token
        }
        if (urls.isEmpty()) return emptyList<String>() to emptyList()


        val results = urls.map { url ->
            processSingleLink(url)
        }

        val names = mutableListOf<String>()
        val allVideos = mutableListOf<String>()

        results.forEach { result ->
            if (result != null) {
                val (avideos, videos) = result
                names.addAll(displayName)
                allVideos.add(avideos.joinToString("#"))
                //allVideos.add(videos.joinToString("#"))
            }
        }

        return names to allVideos


    }

    private fun processSingleLink(url: String): Pair<List<String>, List<String>>? {
        return try {
            val urlInfo = parseShareUrl(url)
            if (urlInfo.containsKey("error")) return null

            val tokenInfo = getShareToken(urlInfo)
            if (tokenInfo?.containsKey("error") == true) {
                SpiderDebug.log("该分享已被取消，无法访问")
                Notify.show("该分享已被取消，无法访问")
                throw RuntimeException("该分享已被取消，无法访问")
            }

            getAllVideos(tokenInfo!!)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseShareUrl(url: String): Map<String, String> {
        var lurl = url
        if ("提取码" in url) lurl = url.replace("提取码:", "?pwd=")

        if ("/share/" !in url) {
            val response = OkHttp.getLocation(url, headers)
            lurl = response ?: ""
        }

        val queryParams = parseQueryParams(lurl)
        val finalUrl = if ("/share/" in url) url.replace("share/init?surl=", "s/1") else url

        return mapOf<String, String>(
            "url" to finalUrl,
            "surl" to (queryParams["surl"]?.firstOrNull() ?: ""),
            "pwd" to (queryParams["pwd"]?.firstOrNull() ?: "")
        )
    }

    private fun getShareToken(urlInfo: Map<String, String>): Map<String, String>? {
        val params = mapOf(
            "t" to System.currentTimeMillis().toString(), "surl" to (urlInfo["surl"] ?: "")
        )

        val data = mapOf("pwd" to (urlInfo["pwd"] ?: ""))

        val response = OkHttp.post(
            "$apiHost/share/verify?t=${params["t"]}&surl=${params["surl"]}", data, headers
        )

        // if ("error" in response) return response
        val json = Json.safeObject(response.body)


        val randsk = json.asJsonObject.get("randsk").asString ?: return mapOf("error" to "获取randsk失败")

        return mapOf(
            "yurl" to (urlInfo["url"]?.split("s/")?.last()?.split("?")?.first() ?: ""),
            "randsk" to randsk,
            "surl" to (urlInfo["surl"] ?: "")
        )
    }

    private fun getAllVideos(tokenInfo: Map<String, String>): Pair<List<String>, List<String>> {
        val videos = mutableListOf<String>()
        val avideos = mutableListOf<String>()
        val seenFolders = mutableSetOf<String>()
        val pendingFolders = mutableListOf<Map<String, Any>>()

        try {
            // 处理根目录
            var currentPage = 1
            var uk = ""
            var shareid = ""

            while (true) {
                val rootFolder = mutableMapOf(
                    "surl" to tokenInfo["surl"]!!,
                    "randsk" to tokenInfo["randsk"]!!,
                    "page" to currentPage,
                    "is_root" to true
                )

                val rootResult = getFolderContents(rootFolder)
                // if ("error" in rootResult) break
                val data = rootResult?.asJsonObject

                val items = data?.get("list")?.asJsonArray ?: break

                if (items.isEmpty) break

                // 第一页获取uk和shareid
                if (currentPage == 1) {
                    uk = data["uk"]?.toString() ?: ""
                    shareid = data["share_id"]?.toString() ?: ""
                    if (uk.isEmpty() || shareid.isEmpty()) return emptyList<String>() to emptyList()
                }

                // 处理items
                items.forEach { item ->
                    if (item.asJsonObject["isdir"].asInt == 1) {
                        val folderPath =
                            "/sharelink$uk-${item.asJsonObject["fs_id"].asString}/${item.asJsonObject["server_filename"].asString}"
                        if (folderPath !in seenFolders) {
                            seenFolders.add(folderPath)
                            pendingFolders.add(
                                mapOf(
                                    "surl" to tokenInfo["surl"]!!,
                                    "randsk" to tokenInfo["randsk"]!!,
                                    "uk" to uk,
                                    "shareid" to shareid,
                                    "dir" to folderPath,
                                    "page" to 1
                                )
                            )
                        }
                    } else if (isVideoFile(item.asJsonObject["server_filename"].asString ?: "")) {
                        addVideo(item.asJsonObject, uk, shareid, tokenInfo, avideos, videos)
                    }
                }

                if (items.size() < 9999) break
                currentPage++
            }

            // 处理子文件夹
            while (pendingFolders.isNotEmpty()) {
                val currentBatch = pendingFolders.toList()
                pendingFolders.clear()

                val results = currentBatch.map { folderInfo ->
                    getFolderContents(folderInfo)
                }

                results.forEachIndexed { i, result ->
                    val folderInfo = currentBatch[i]
                    //if ("error" in result) return@forEachIndexed

                    val items = result?.asJsonObject?.get("list")?.asJsonArray ?: return@forEachIndexed

                    items.forEach { item ->
                        if (item.asJsonObject["isdir"].asInt == 1) {
                            val folderPath = item.asJsonObject["path"].asString ?: ""
                            if (folderPath !in seenFolders) {
                                seenFolders.add(folderPath)
                                pendingFolders.add(
                                    mapOf(
                                        "surl" to tokenInfo["surl"]!!,
                                        "randsk" to tokenInfo["randsk"]!!,
                                        "uk" to folderInfo["uk"]!!,
                                        "shareid" to folderInfo["shareid"]!!,
                                        "dir" to folderPath,
                                        "page" to 1
                                    )
                                )
                            }
                        } else if (isVideoFile(item.asJsonObject["server_filename"].asString ?: "")) {
                            addVideo(
                                item.asJsonObject,
                                folderInfo["uk"]?.toString() ?: "",
                                folderInfo["shareid"]?.toString() ?: "",
                                tokenInfo,
                                avideos,
                                videos
                            )
                        }
                    }

                    if (items.size() >= 9999) {
                        pendingFolders.add(folderInfo.toMutableMap().apply {
                            this["page"] = (this["page"] as Int) + 1
                        })
                    }
                }
            }

            return avideos to videos
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList<String>() to emptyList()
        }
    }

    private fun isVideoFile(string: String): Boolean {

        return string.substringAfterLast(".").lowercase(Locale.ROOT) in MEDIA
    }

    private fun addVideo(
        item: JsonObject,
        uk: String,
        shareid: String,
        tokenInfo: Map<String, String>,
        avideos: MutableList<String>,
        videos: MutableList<String>
    ) {
        val sizeStr = formatSize(item["size"].asLong)
        val name = item["server_filename"] ?: ""

        val originalData = mapOf(
            "uk" to uk,
            "shareid" to shareid,
            "fid" to item["fs_id"],
            "randsk" to tokenInfo["randsk"],
            "pname" to name,
            "qtype" to "original"
        )

        val previewData = mapOf(
            "uk" to uk,
            "fid" to item["fs_id"],
            "shareid" to shareid,
            "surl" to tokenInfo["yurl"],
            "pname" to name,
            "qtype" to "preview"
        )

        avideos.add(
            "[$sizeStr]$name$${
                Util.base64Encode(Json.toJson(originalData).toByteArray())
            }"
        )
        videos.add(
            "[$sizeStr]$name$${
                Util.base64Encode(Json.toJson(previewData).toByteArray())
            }"
        )
    }

    private fun getFolderContents(folderInfo: Map<String, Any>): JsonObject? {
        val params = if (folderInfo.containsKey("dir")) {
            mapOf(
                "uk" to folderInfo["uk"]!!.toString(),
                "shareid" to folderInfo["shareid"]!!.toString(),
                "page" to folderInfo["page"].toString(),
                "num" to "9999",
                "dir" to URLEncoder.encode(folderInfo["dir"]!!.toString(), Charset.defaultCharset().name()),
                "desc" to "0",
                "order" to "name",
            )
        } else {
            mapOf(
                "page" to folderInfo["page"].toString(),
                "num" to "9999",
                "shorturl" to folderInfo["surl"]!!.toString(),
                "root" to "1",
                "desc" to "0",
                "order" to "name",
            )
        }


        val tempHeader = headers.toMutableMap()
        tempHeader.put("Cookie", "BDCLND=${folderInfo["randsk"]}")
        val result = OkHttp.string("$apiHost/share/list", params, tempHeader)
        return Json.safeObject(result)

    }


    private fun parseQueryParams(url: String): Map<String, List<String>> {
        val query = url.substringAfter(
            "?"
        ).substringBefore('#')
        return query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to listOf(kv[1]) else null   // 无 '=' 的片段安全跳过
        }.toMap()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }


    fun getBdUid(): String? {
        if (cache["uid"] != null) {
            return cache["uid"]
        }
        val tempHeader = headers.toMutableMap()
        tempHeader.put("Cookie", cookies)
        try {
            val response = OkHttp.string(

                "https://mbd.baidu.com/userx/v1/info/get?appname=baiduboxapp&fields=%20%20%20%20%20%20%20%20%5B%22bg_image%22,%22member%22,%22uid%22,%22avatar%22,%20%22avatar_member%22%5D&client&clientfrom&lang=zh-cn&tpl&ttt",
                emptyMap<String, String>(),
                tempHeader

            )

            val responseJson = Json.safeObject(response)
            val user = responseJson["data"].asJsonObject

            val fields = user?.get("fields")?.asJsonObject

            val uidValue = fields?.get("uid")?.asString

            if (uidValue != null) {
                cache["uid"] = uidValue
                return uidValue
            } else {
                throw Exception("Failed to retrieve UID from Baidu Drive.")
            }
        } catch (e: Exception) {
            SpiderDebug.log("获取百度网盘用户ID失败: ${e.message}")
            return ""
        }
    }

    fun _getSign(videoData: JsonObject): Pair<String, String> {
        val tempHeader = headers.toMutableMap()
        tempHeader.put("Cookie", cookies)
        val response: String? = OkHttp.string(
            "${apiHost}/share/tplconfig", mapOf<String, String>(
                "surl" to videoData["surl"].asString, "fields" to "cfrom_id,Espace_info,card_info,sign,timestamp"
            ),

            tempHeader
        )
        return try {
            val data = Json.safeObject(response)["data"].asJsonObject

            data["sign"].asString to data["timestamp"].asString
        } catch (_: Exception) {
            "" to ""
        }
    }


    fun _getDownloadUrl(videoData: JsonObject): String {
        return try {
            var cookie = ""
            val BDCLND = "BDCLND=" + videoData["randsk"].asString
// 更新Cookie中的BDCLND值
            if (!this.cookies.contains("BDCLND")) {
                cookie = this.cookies + ";" + BDCLND

            } else {
                cookie = this.cookies.split(";").joinToString(";") {
                    if (it.contains("BDCLND")) {
                        BDCLND
                    } else {
                        it
                    }
                }

            }


            val transferHeaders = mapOf(
                "User-Agent" to "Android",
                "Connection" to "Keep-Alive",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept-Language" to "zh-CN,zh;q=0.8",
                "charset" to "UTF-8",
                "Referer" to "https://pan.baidu.com",
                "Cookie" to cookie
            )
            // 先清空文件夹在创建文件夹

            if (deleteTag == 0) {
                _deleteTransferFile("/$saveDirName")
                //创建路径
                createSaveDir()
                deleteTag = 1
            }


            val data =
                "from=${videoData["uk"].asString}&shareid=${videoData["shareid"].asString}&ondup=newcopy&path=/${saveDirName}&fsidlist=[${videoData["fid"].asString}]"

            var to = ""
            for (i in 1..30) {


                val response =
                    OkHttp.post("${apiHost}/share/transfer?${data}", emptyMap<String, String>(), transferHeaders)

                val result = Json.safeObject(response.body)

                try {
                    to = (result["extra"].asJsonObject)["list"].asJsonArray[0].asJsonObject["to"].asString
                    // videoData["to"] = to
                    if (to.isNotEmpty()) {
                        SpiderDebug.log("成功转存文件到: $to")
                        break
                    }
                } catch (e: Exception) {
                    SpiderDebug.log("解析转存响应出错: ${e.message}")
                    continue
                }
            }

            if (to.isEmpty()) {
                SpiderDebug.log("转存文件失败，无法获取下载链接")
                return ""
            }

            val mediaInfoHeaders = mapOf(

                "User-Agent" to "netdisk;1.4.2;22021211RC;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;",
                "Connection" to "Keep-Alive",
                "Accept-Language" to "zh-CN,zh;q=0.8",
                "charset" to "UTF-8",
                "Cookie" to cookie
            ).toMutableMap()


            val mediaInfoParams = mapOf(
                "type" to "M3U8_FLV_264_480", "path" to "/$to", "clienttype" to "80", "origin" to "dlna"
            )


            val mediaInfoResponse: String? = OkHttp.string(
                "${apiHost}/api/mediainfo", mediaInfoParams, mediaInfoHeaders
            )
            val responseJson = Json.safeObject(mediaInfoResponse)
            val info = responseJson["info"].asJsonObject
            val downloadUrl = info["dlink"].asString
            SpiderDebug.log("获取到下载链接: $downloadUrl")
            downloadUrl
        } catch (e: Exception) {
            SpiderDebug.log("获取下载链接过程中出错: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    fun getVideoUrl(videoData: JsonObject, flag: String): Map<String, Any> {
        return try {
            val bdUid = getBdUid()
            SpiderDebug.log("获取百度网盘用户ID: $bdUid")

            if (flag.contains("原画")) {

                var headersApp = mapOf("User-Agent" to "netdisk;P2SP;2.2.91.136;android-android;")

                var downloadUrl = _getAppDownloadUrl(videoData)
                if (downloadUrl.isEmpty()) {

                    headersApp = mapOf(

                        "User-Agent" to "netdisk;1.4.2;22021211RC;android-android;12;JSbridge4.4.0;jointBridge;1.1.0;"
                    )

                    downloadUrl = _getDownloadUrl(videoData)
                }
                if (downloadUrl.isNotEmpty()) {

                    val result = mapOf(
                        "parse" to "0", "url" to downloadUrl, "header" to headersApp
                    )

                    result
                } else {
                    _handleError
                }
            } else {
                val (sign, time) = _getSign(videoData)
                if (sign.isEmpty() || time.isEmpty()) {
                    return _handleError
                }
                val plist = _getPlayList(videoData, sign, time)
                val tempHeader = headers.toMutableMap()
                tempHeader.put("Cookie", cookies)
                mapOf(
                    "parse" to "0", "url" to plist[0], "header" to tempHeader
                )
            }
        } catch (e: Exception) {
            SpiderDebug.log("获取播放链接失败: ${e.message}")
            _handleError
        }
    }

    private fun _getAppDownloadUrl(videoData: JsonObject): String {
        return try {
            val headers = mapOf<String, String>(

                "User-Agent" to "netdisk;P2SP;2.2.91.136;android-android;",
                "Connection" to "Keep-Alive",
                "Accept-Language" to "zh-CN,zh;q=0.8",
                "charset" to "UTF-8",
                "cookie" to cookies
            )
            val uid = this.getBdUid()
            val t = System.currentTimeMillis()
            val params = mapOf<String, String>(
                "shareid" to videoData["shareid"].asString,
                "uk" to videoData["uk"].asString,
                "fid" to videoData["fid"].asString,
                "sekey" to unquote(videoData["randsk"].asString),
                "origin" to "dlna",
                "devuid" to "73CED981D0F186D12BC18CAE1684FFD5|VSRCQTF6W",
                "clienttype" to "1",
                "channel" to "android_12_zhao_bd-netdisk_1024266h",
                "version" to "11.30.2",
                "time" to t.toString()
            ).toMutableMap()

            val randstr = this.sha1(
                this.sha1(
                    Util.findByRegex(
                        "BDUSS=(.+?);", cookies, 1
                    )
                ) + uid + "ebrcUYiuxaZv2XGu7KIYKxUrqfnOfpDF$t${params["devuid"]}11.30.2ae5821440fab5e1a61a025f014bd8972"
            )

            params.put("rand", randstr)

            val response = OkHttp.string(
                "${apiHost}/share/list", params, headers
            )
            val json = Json.safeObject(response)
            val dlink = json["list"].asJsonArray[0].asJsonObject["dlink"].asString

            /* val url = response["data"] as Map<String, Any>
             val list = url["list"] as List<Map<String, Any>>
             val dlink = list[0]["dlink"] as String

             val pDataResponse = client.get(dlink) {
                 headers { this@_getAppDownloadUrl.headers.forEach { name, value -> append(name, value) } }
                 cookies?.let { setCookie(it) }
                 followRedirects = false
                 timeout.socketTimeoutMillis = 10000
             }

             val pUrl = pDataResponse.headers[HttpHeaders.Location]?.toString()
             pUrl ?: dlink*/
            dlink
        } catch (e: Exception) {
            SpiderDebug.log("获取下载链接失败: ${e.message}")
            ""
        }
    }

    private fun _getPlayList(videoData: JsonObject, sign: String, time: String): List<String> {
        val hz = getPlayFormatList()
        val plist = mutableListOf<String>()

        for (quality in hz) {
            val url =
                ("${apiHost}/share/streaming?" + "uk=${videoData["uk"].asString}&" + "fid=${videoData["fid"].asString}&" + "sign=$sign&" + "timestamp=$time&" + "shareid=${videoData["shareid"].asString}&" + "type=M3U8_AUTO_${
                    quality.replace(
                        "P", ""
                    )
                }")
            plist.add(url)
        }

        return plist
    }

    /**
     * 创建保存目录
     */
    fun createSaveDir(): Long? {
        var saveDirId: Long? = null
        // 创建保存目录
        if (cookies.isEmpty()) {
            return null
        }
        val tempHeader = headers.toMutableMap()
        tempHeader.put("Cookie", cookies)
        return try {
            val listResp = OkHttp.string(
                "${apiHost}/api/list", mapOf(
                    "dir" to "/",
                    "order" to "name",
                    "desc" to "0",
                    "showempty" to "0",
                    "web" to "1",
                    "app_id" to "250528"
                ), tempHeader
            )
            val json = Json.safeObject(listResp)

            if (json["errno"].asInt != 0) {
                return null
            }

            val drpyDir = json["list"].asJsonArray.find { item ->
                item.asJsonObject.get("isdir").asInt == 1 && item.asJsonObject.get("server_filename").asString == saveDirName

            }

            if (drpyDir != null) {
                saveDirId = drpyDir.asJsonObject.get("fs_id").asLong
                return saveDirId
            }

            val createResp = OkHttp.post(
                "${apiHost}/api/create?a=commit&bdstoken=${getBdstoken()}&clienttype=0&app_id=250528&web=1&dp-logid=73131200762376420075",
                mapOf(
                    "path" to "//$saveDirName",
                    "isdir" to "1",
                    "block_list" to "[]",

                    ),
                tempHeader
            )
            val createJson = Json.safeObject(createResp.body)



            saveDirId = createJson["fs_id"].asLong
            saveDirId

        } catch (e: Exception) {
            SpiderDebug.log("创建保存目录失败: ${e.message}")
            null
        }
    }

    fun getBdstoken(): String {
        if (cache["bdstoken"] != null) {
            return cache["bdstoken"]!!
        }
        val tempHeader = headers.toMutableMap()
        tempHeader.put("Cookie", cookies)
        val userInfo = OkHttp.string(
            "${apiHost}/api/gettemplatevariable?clienttype=0&app_id=250528&web=1&fields=[\"bdstoken\",\"token\",\"uk\",\"isdocuser\",\"servertime\"]",
            mapOf(),
            tempHeader

        )
        val json = Json.safeObject(userInfo)

        val bdstoken: String? = json["result"]?.asJsonObject?.get("bdstoken")?.asString
        cache["bdstoken"] = bdstoken ?: ""
        return bdstoken ?: ""
    }


    private fun _deleteTransferFile(filePath: String) {
        try {
            val url =
                "$apiHost/api/filemanager?async=2&onnest=fail&opera=delete&bdstoken=${getBdstoken()}&newVerify=1&clienttype=0&app_id=250528&web=1&dp-logid=39292100213290200076"
            val params = mapOf(
                "filelist" to "[\"$filePath\"]"
            )

            val headers = mutableMapOf(
                "User-Agent" to "Android",
                "Connection" to "Keep-Alive",
                "Accept-Encoding" to "br,gzip",
                "Content-Type" to "application/x-www-form-urlencoded; charset=utf-8",
                "Accept-Language" to "zh-CN,zh;q=0.8",
                "charset" to "UTF-8",
                "Cookie" to cookies,
            )


            val response = OkHttp.post(url, params, headers)



            SpiderDebug.log("删除文件响应: ${response.body}")
            SpiderDebug.log("响应状态码: ${response.code}")
        } catch (e: Exception) {
            SpiderDebug.log("删除文件出错: ${e.message}")
            e.printStackTrace()
        }
    }


    private fun unquote(encoded: String): String {
        return encoded.replace("%([0-9A-Fa-f]{2})".toRegex()) { match ->
            Integer.parseInt(match.groupValues[1], 16).toChar().toString()
        }
    }

    private fun sha1(input: String): String {

        return Util.sha1Hex(input)
    }

    private val _handleError = mapOf(
        "parse" to "1", "msg" to "Error retrieving video URL"
    )


    fun getVod(shareUrl: String): Vod {
        val (froms, urls) = processShareLinks(listOf(shareUrl))
        val builder = VodPlayBuilder()
        for (i in froms.indices) {
            val playUrls = mutableListOf<VodPlayBuilder.PlayUrl>();
            for (url in urls[i].split("#")) {
                val arr = url.split("$")
                val play = VodPlayBuilder.PlayUrl()
                play.name = arr[0]
                play.url = arr[1]

                playUrls.add(play)

            }
            builder.append(froms[i], playUrls)
        }
        val buildResult = builder.build();

        val vod = Vod()
        vod.setVodId(shareUrl)
        vod.setVodPic("")
        vod.setVodYear("")
        vod.setVodName("")
        vod.setVodContent("")
        vod.setVodPlayFrom(buildResult.vodPlayFrom)
        vod.setVodPlayUrl(buildResult.vodPlayUrl)
        return vod
    }

    fun playerContent(json: JsonObject, flag: String): String {
        val play = getVideoUrl(json, flag);
        val header = play["header"] as Map<String, String>
        return Result.get().url( Launcher.buildProxyUrl(play["url"] as String, header)).octet().header(header)
            .string();
    }

    fun getPlayFormatList(): Array<String> {
        return listOf("1080P").toTypedArray()
    }

    fun proxyVideo(params: MutableMap<String, String>): Array<Any> {
        return emptyList<Any>().toTypedArray()
    }


}
