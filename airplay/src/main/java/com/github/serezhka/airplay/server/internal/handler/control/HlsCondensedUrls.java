package com.github.serezhka.airplay.server.internal.handler.control;

import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expand {@code YT-EXT-CONDENSED-URL} media playlists into full segment URIs. */
@Slf4j
final class HlsCondensedUrls {

    private static final Pattern ATTR =
            Pattern.compile("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))");

    private HlsCondensedUrls() {
    }

    static MediaPlaylist expand(MediaPlaylist mediaPlaylist) {
        // Matcher.results() needs API 34; this app's floor is API 21, so collect matches by hand.
        Map<String, String> condensedUrl = new LinkedHashMap<>();
        mediaPlaylist.comments().stream()
                .filter(comment -> comment.startsWith("YT-EXT-CONDENSED-URL:"))
                .map(comment -> comment.replace("YT-EXT-CONDENSED-URL:", ""))
                .forEach(attributes -> {
                    Matcher m = ATTR.matcher(attributes);
                    while (m.find()) {
                        condensedUrl.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
                    }
                });
        if (condensedUrl.isEmpty()) {
            return mediaPlaylist;
        }
        var prefix = condensedUrl.get("PREFIX");
        var params = condensedUrl.get("PARAMS");
        var baseUri = condensedUrl.get("BASE-URI");
        if (prefix == null || params == null || baseUri == null) {
            return mediaPlaylist;
        }
        var paramNames = params.split(",");
        boolean matching = mediaPlaylist.mediaSegments().stream().allMatch(segment ->
                segment.uri().replaceFirst(Pattern.quote(prefix), "").split("/").length == paramNames.length);
        if (!matching) {
            log.warn("Condensed URL param count mismatch, leaving playlist unchanged");
            return mediaPlaylist;
        }
        return MediaPlaylist.builder()
                .from(mediaPlaylist)
                .mediaSegments(mediaPlaylist.mediaSegments().stream()
                        .map(segment -> {
                            var paramValues = segment.uri().replaceFirst(Pattern.quote(prefix), "").split("/");
                            var paramResult = new StringBuilder();
                            for (int i = 0; i < paramNames.length; i++) {
                                paramResult.append("/").append(paramNames[i]).append("/").append(paramValues[i]);
                            }
                            return MediaSegment.builder().from(segment).uri(baseUri + paramResult).build();
                        })
                        .collect(java.util.stream.Collectors.toList()))
                .build();
    }
}
