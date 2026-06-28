package com.ecommerce.utils;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class ScreenshotUtils {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String DIR = System.getProperty("screenshot.dir", "build/screenshots");

    private ScreenshotUtils() {}

    public static Path capture(Page page, String testName) throws IOException {
        String filename = testName + "_" + LocalDateTime.now().format(FMT) + ".png";
        Path path = Paths.get(DIR, filename);
        path.getParent().toFile().mkdirs();
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));
        log.info("Screenshot saved: {}", path);
        return path;
    }
}
