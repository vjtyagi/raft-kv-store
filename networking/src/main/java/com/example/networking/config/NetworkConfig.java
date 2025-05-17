package com.example.networking.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Slf4j
@NoArgsConstructor
public class NetworkConfig {
    private int connetionTimeoutMs = 3000; // default 3 seconds
    private int readTimeoutMs = 3000;

    // Node URLs: nodeId -> URL mapping
    private Map<String, String> nodeUrls = new HashMap<>();

    public String getNodeUrl(String nodeId) {
        String url = nodeUrls.get(nodeId);
        if (url == null) {
            url = resolveNodeUrl(nodeId);
            nodeUrls.put(nodeId, url);
            log.info("Auto-resolved URL for node {}: {}", nodeId, url);
        }
        return url;
    }

    public String resolveNodeUrl(String nodeId) {
        return "http://" + nodeId + ":8080";
    }

    public RestTemplate createRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // configure timeouts
        restTemplate.setRequestFactory(new SimpleClientHttpRequestFactory());
        SimpleClientHttpRequestFactory rf = (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
        rf.setConnectTimeout(connetionTimeoutMs);
        rf.setReadTimeout(readTimeoutMs);

        return restTemplate;
    }

    public void addNodeUrl(String nodeId, String url) {
        nodeUrls.put(nodeId, url);
    }
}
