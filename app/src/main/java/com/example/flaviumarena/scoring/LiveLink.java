package com.example.flaviumarena.scoring;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The CricHeroes link of a cricket fixture.
 *
 * FLAVIUM Arena does not read scores from CricHeroes. It only stores the address of the
 * match page that the organiser created there, and opens that page when "Open Live
 * Score" is tapped. This class checks that the stored address really is a secure
 * CricHeroes address, so the button can never be made to open some other website.
 */
public final class LiveLink {

    private static final String[] HOSTS = {"cricheroes.com", "cricheroes.in"};

    private LiveLink() {
    }

    /** Returns null when the link is acceptable (or empty), otherwise what is wrong. */
    public static String problemWith(String link) {
        if (link == null || link.trim().isEmpty()) {
            return null; // the link is optional
        }
        String text = link.trim();
        if (text.contains(" ")) {
            return "The link must not contain spaces.";
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return "This is not a valid web address.";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "The link must start with https://";
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        for (String allowed : HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                String path = uri.getPath() == null ? "" : uri.getPath();
                if (path.length() <= 1) {
                    return "Paste the link of the match itself, not just the CricHeroes home "
                            + "page.";
                }
                return null;
            }
        }
        return "Only CricHeroes match links are accepted (cricheroes.com).";
    }
}
