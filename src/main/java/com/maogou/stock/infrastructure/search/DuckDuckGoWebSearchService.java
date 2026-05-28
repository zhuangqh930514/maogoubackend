package com.maogou.stock.infrastructure.search;

import com.maogou.stock.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DuckDuckGoWebSearchService implements WebSearchService {

    private static final Pattern RESULT_LINK_PATTERN = Pattern.compile("(?is)<a[^>]+class=[\"'][^\"']*result__a[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern SNIPPET_PATTERN = Pattern.compile("(?is)<(?:a|div)[^>]+class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>");

    private final RestTemplate restTemplate;
    private final AppProperties properties;

    public DuckDuckGoWebSearchService(@Qualifier("webSearchRestTemplate") RestTemplate restTemplate, AppProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    public WebSearchContext search(String query, int limit) {
        AppProperties.WebSearch config = properties.getWebSearch();
        if (!config.isEnabled()) {
            return WebSearchContext.failure("后端未启用联网搜索，请设置 MAOGOU_WEB_SEARCH_ENABLED=true");
        }
        if (!"duckduckgo".equalsIgnoreCase(config.getProvider())) {
            return WebSearchContext.failure("暂不支持的搜索提供方：" + config.getProvider());
        }
        if (query == null || query.isBlank()) {
            return WebSearchContext.failure("搜索关键词为空");
        }

        int maxResults = Math.max(1, Math.min(limit, Math.max(1, config.getMaxResults())));
        String searchQuery = compact(query, 120);
        String url = UriComponentsBuilder.fromUriString(config.getDuckDuckGoHtmlUrl())
                .queryParam("q", searchQuery)
                .queryParam("kl", "cn-zh")
                .build()
                .encode()
                .toUriString();

        try {
            String html = restTemplate.getForObject(url, String.class);
            List<WebSearchItem> results = parseResults(html, maxResults);
            if (results.isEmpty()) {
                return WebSearchContext.failure("联网搜索未返回可用结果");
            }
            return WebSearchContext.success(results);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "未知搜索错误" : ex.getMessage();
            return WebSearchContext.failure(message);
        }
    }

    private static List<WebSearchItem> parseResults(String html, int limit) {
        List<WebSearchItem> results = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return results;
        }

        Matcher matcher = RESULT_LINK_PATTERN.matcher(html);
        List<LinkMatch> links = new ArrayList<>();
        while (matcher.find()) {
            links.add(new LinkMatch(matcher.start(), matcher.end(), matcher.group(1), matcher.group(2)));
        }

        for (int i = 0; i < links.size() && results.size() < limit; i++) {
            LinkMatch current = links.get(i);
            int nextStart = i + 1 < links.size() ? links.get(i + 1).start : html.length();
            String snippet = extractSnippet(html.substring(current.end, nextStart));
            String title = cleanHtml(current.titleHtml);
            String normalizedUrl = normalizeUrl(current.href);
            if (title.isBlank() || normalizedUrl.isBlank() || normalizedUrl.contains("duckduckgo.com/y.js")) {
                continue;
            }
            results.add(new WebSearchItem(title, normalizedUrl, snippet));
        }
        return results;
    }

    private static String extractSnippet(String block) {
        Matcher matcher = SNIPPET_PATTERN.matcher(block);
        if (!matcher.find()) {
            return "";
        }
        return compact(cleanHtml(matcher.group(1)), 260);
    }

    private static String normalizeUrl(String href) {
        String value = HtmlUtils.htmlUnescape(href == null ? "" : href).trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (value.startsWith("/")) {
            value = "https://duckduckgo.com" + value;
        }
        String decoded = extractDuckDuckGoTarget(value);
        return decoded == null || decoded.isBlank() ? value : decoded;
    }

    private static String extractDuckDuckGoTarget(String href) {
        int start = href.indexOf("uddg=");
        if (start < 0) {
            return null;
        }
        int valueStart = start + "uddg=".length();
        int valueEnd = href.indexOf('&', valueStart);
        String encoded = valueEnd >= 0 ? href.substring(valueStart, valueEnd) : href.substring(valueStart);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static String cleanHtml(String html) {
        if (html == null) {
            return "";
        }
        return HtmlUtils.htmlUnescape(html
                        .replaceAll("(?is)<script.*?</script>", " ")
                        .replaceAll("(?is)<style.*?</style>", " ")
                        .replaceAll("(?is)<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String compact(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private record LinkMatch(int start, int end, String href, String titleHtml) {
    }
}
