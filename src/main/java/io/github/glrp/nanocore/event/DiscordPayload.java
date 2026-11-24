package io.github.glrp.nanocore.event;

import java.util.List;

/** The JSON body of a Discord webhook message with one embed. Pure logic. */
public final class DiscordPayload {

    private DiscordPayload() {
    }

    public record Field(String name, String value) {
    }

    public static String json(String username, String title, String description, int color, List<Field> fields) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"username\":").append(str(username)).append(",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{");
        b.append("\"title\":").append(str(cut(title, 256)));
        if (description != null && !description.isBlank()) b.append(",\"description\":").append(str(cut(description, 4000)));
        b.append(",\"color\":").append(color & 0xFFFFFF);
        if (fields != null && !fields.isEmpty()) {
            b.append(",\"fields\":[");
            for (int i = 0; i < Math.min(25, fields.size()); i++) {
                if (i > 0) b.append(',');
                Field f = fields.get(i);
                b.append("{\"name\":").append(str(cut(f.name(), 256))).append(",\"value\":").append(str(cut(f.value(), 1024))).append(",\"inline\":true}");
            }
            b.append(']');
        }
        b.append("}]}");
        return b.toString();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** A JSON string literal. */
    static String str(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
