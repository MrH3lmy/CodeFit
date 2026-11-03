package com.codefit.ui;

import static org.junit.jupiter.api.Assertions.*;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

class SystemPaletteTest {
    @Test
    void everyThemeKeepsSemanticContractAndReadableInkAcrossSurfaces() throws Exception {
        String css;
        try (var input = getClass().getResourceAsStream("/css/tokens.css")) {
            assertNotNull(input);
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String theme : List.of("dark", "light")) {
            var block = Pattern.compile("\\.theme-" + theme + "\\s*\\{([^}]+)}").matcher(css);
            assertTrue(block.find());
            var matcher =
                    Pattern.compile("(-color-[a-z-]+):\\s*(#[A-Fa-f0-9]{6});")
                            .matcher(block.group(1));
            Map<String, Color> colors = new HashMap<>();
            while (matcher.find())
                colors.put(matcher.group(1).substring(7), Color.web(matcher.group(2)));
            assertTrue(
                    colors.keySet()
                            .containsAll(
                                    List.of(
                                            "background",
                                            "surface",
                                            "surface-raised",
                                            "border",
                                            "text-primary",
                                            "text-secondary",
                                            "accent",
                                            "accent-hover",
                                            "success",
                                            "warning",
                                            "danger")));
            for (String surface : List.of("background", "surface", "surface-raised")) {
                for (String ink :
                        List.of(
                                "text-primary",
                                "text-secondary",
                                "accent",
                                "selection",
                                "success",
                                "warning",
                                "danger")) {
                    assertTrue(
                            contrast(colors.get(ink), colors.get(surface)) >= 4.5,
                            theme + " " + ink + " on " + surface);
                }
            }
            for (String fill : List.of("accent", "accent-hover"))
                assertTrue(
                        contrast(colors.get("on-accent"), colors.get(fill)) >= 4.5,
                        theme + " on " + fill);
            for (String fill : List.of("success", "warning", "danger"))
                assertTrue(
                        contrast(colors.get("on-status"), colors.get(fill)) >= 4.5,
                        theme + " on " + fill);
        }
    }

    private static double contrast(Color a, Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private static double luminance(Color c) {
        return .2126 * linear(c.getRed())
                + .7152 * linear(c.getGreen())
                + .0722 * linear(c.getBlue());
    }

    private static double linear(double v) {
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }
}
