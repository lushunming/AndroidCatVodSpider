package com.github.catvod.server;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public interface Process {

    boolean isRequest(NanoHTTPD.IHTTPSession session, String path);

    NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String path, Map<String, String> files);
}
