package dev.demo.selection.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record WebSettings(long term, long studentFrom, long studentTo) { }
