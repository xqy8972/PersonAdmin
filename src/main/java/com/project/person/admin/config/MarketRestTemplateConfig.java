package com.project.person.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 行情接口专用的 RestTemplate：
 * - 腾讯/新浪行情返回 GBK 编码文本，放在最前优先按 GBK 解码；
 * - 短超时，行情拉取失败不能拖垮主流程；
 * - 腾讯接口需带 Referer: https://gu.qq.com/，否则可能被拒。
 */
@Configuration
public class MarketRestTemplateConfig {

    @Bean
    public RestTemplate marketRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);

        RestTemplate restTemplate = new RestTemplate(factory);
        List<HttpMessageConverter<?>> converters = new ArrayList<>(restTemplate.getMessageConverters());
        converters.add(0, new StringHttpMessageConverter(Charset.forName("GBK")));
        restTemplate.setMessageConverters(converters);

        restTemplate.getInterceptors().add((request, body, execution) -> {
            String host = request.getURI().getHost();
            if (host != null && host.contains("gtimg.cn")) {
                request.getHeaders().add("Referer", "https://gu.qq.com/");
            } else if (host != null && host.contains("eastmoney.com")) {
                request.getHeaders().add("Referer", "https://fund.eastmoney.com/");
            }
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
