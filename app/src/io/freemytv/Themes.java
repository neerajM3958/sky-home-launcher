package io.freemytv;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registry for visual themes. A theme supplies role colors for both schemes; theme selection is separate from light/dark. */
final class Themes {
    static final String GLASS = "glass", MATERIAL_EXPRESSIVE = "material3-expressive";

    interface Theme {
        String id();
        String label();
        int color(int role, boolean dark);
        boolean expressive();
    }

    private static final Map<String, Theme> REGISTRY = new LinkedHashMap<>();

    static {
        register(new Theme() {
            private final int[][] colors = {
                {0xFFF5F5F7, 0xFF111113, 0xFF1D1D1F},
                {0xFF6E6E73, 0xFFB0B0B8, 0xFF2C2C2E},
                {0xFF6E6E73, 0xFF8E8E93, 0xFF48484A}
            };
            public String id() { return GLASS; }
            public String label() { return "Glass"; }
            public int color(int role, boolean dark) {
                int i = Math.max(0, Math.min(colors.length - 1, role));
                return colors[i][dark ? 1 : 0];
            }
            public boolean expressive() { return false; }
        });
        register(new Theme() {
            public String id() { return MATERIAL_EXPRESSIVE; }
            public String label() { return "Material 3 Expressive"; }
            public int color(int role, boolean dark) { return MaterialExpressive.paletteColor(role, dark); }
            public boolean expressive() { return true; }
        });
    }

    private Themes() {}

    static synchronized void register(Theme theme) {
        if (theme == null || theme.id() == null || theme.id().trim().isEmpty()) throw new IllegalArgumentException("Theme needs an id");
        REGISTRY.put(theme.id(), theme);
    }

    static synchronized boolean has(String id) { return id != null && REGISTRY.containsKey(id); }
    static synchronized Theme get(String id) { return REGISTRY.get(has(id) ? id : GLASS); }
    static synchronized String[] ids() { return REGISTRY.keySet().toArray(new String[0]); }
    static Theme current() { return get(Prefs.look()); }
    static boolean expressive() { return current().expressive(); }
}
