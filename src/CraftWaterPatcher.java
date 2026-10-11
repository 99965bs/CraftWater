import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WaterBuild - CraftWater builder (local build).
 *
 * Downloads the patch pack straight from GitHub (releases + assets, or a
 * pack.yml committed in the repo tree), lets the user pick a release and
 * toggle patch modules, then verifies Java 21+, downloads the pristine
 * CraftBukkit base and
 * the two Maven dependency jars (checking sha256), installs them into an
 * isolated Maven repo, bootstraps Maven, applies the selected .cw patches with
 * baseFiles integrity checks, and runs `mvn package`.
 *
 * The pack contains only our patches; no Minecraft source is bundled here.
 */
public class CraftWaterPatcher extends JFrame {

    public static void main(String[] args) throws Exception {
        Path root = Patcher.findRoot();
        Patcher patcher = new Patcher(root);

        if (args.length > 0 && args[0].equals("--selftest")) {
            String rel = args[1];
            Path expected = args.length > 2 ? Paths.get(args[2]) : null;
            boolean ok = patcher.selfTest(rel, expected, s -> System.out.println(s));
            System.exit(ok ? 0 : 1);
        }

        if (args.length > 0 && args[0].equals("--build")) {
            String rel = args[1];
            Pack pk = patcher.loadPack();
            pk.select(rel);
            List<String> chosen = new ArrayList<>();
            if (args.length > 2 && !args[2].equalsIgnoreCase("all"))
                chosen.addAll(Arrays.asList(args[2].split(",")));
            else
                for (Unit u : pk.units()) chosen.add(u.id);
            try {
                patcher.build(rel, chosen, s -> System.out.println(s), (o, m, st) -> {});
                System.out.println("BUILD OK");
            } catch (Exception e) {
                System.out.println("BUILD FAILED: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
        }

        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (Exception ignore) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore2) {}
        }

        final Patcher p = patcher;
        SwingUtilities.invokeLater(() -> new CraftWaterPatcher(p).setVisible(true));
    }

    private final Patcher patcher;
    private Pack pack;
    private boolean russian = false;

    private final Map<String, Boolean> selected = new LinkedHashMap<>();

    private JComboBox<String> releaseBox;
    private JPanel quickPanel, detailedPanel;
    private JButton buildBtn, langBtn, themeBtn, refreshBtn;
    private JTextArea logArea;
    private JProgressBar overallBar, moduleBar;
    private JLabel statusLabel, releaseLabel, baseLabel, warnLabel, titleLabel;
    private JTabbedPane tabs;
    private JPanel topPanel;
    private boolean dark = false;
    private Boolean appliedDark;

    CraftWaterPatcher(Patcher patcher) {
        super("WaterBuild");
        this.patcher = patcher;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(960, 700);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        dark = "dark".equalsIgnoreCase(System.getProperty("waterbuild.theme", "light"));

        try {
            ImageIcon icon = patcher.loadIcon();
            if (icon != null) setIconImage(icon.getImage());
        } catch (Exception ignore) {}

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        applyLocale();
        loadReleasesAsync();
    }

    // --------------------------------------------------------------- layout
    private JComponent buildTop() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(new Color(0x1E, 0x6F, 0xB4));
        topPanel = top;
        top.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel title = new JLabel("WaterBuild");
        titleLabel = title;
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        top.add(title, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        releaseLabel = new JLabel();
        releaseLabel.setForeground(Color.WHITE);
        releaseBox = new JComboBox<>();
        releaseBox.addActionListener(e -> refreshRelease());
        refreshBtn = new JButton("\u21BB");
        refreshBtn.setToolTipText("Refresh from GitHub");
        refreshBtn.setMargin(new Insets(2, 8, 2, 8));
        refreshBtn.addActionListener(e -> loadReleasesAsync());
        langBtn = new JButton("RU");
        langBtn.addActionListener(e -> { russian = !russian; applyLocale(); });
        themeBtn = new JButton();
        themeBtn.addActionListener(e -> { dark = !dark; applyLocale(); });
        right.add(releaseLabel);
        right.add(releaseBox);
        right.add(refreshBtn);
        right.add(langBtn);
        right.add(themeBtn);
        right.add(langBtn);
        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout());

        baseLabel = new JLabel();
        baseLabel.setBorder(new EmptyBorder(4, 10, 0, 10));
        baseLabel.setFont(baseLabel.getFont().deriveFont(Font.PLAIN, 11f));
        center.add(baseLabel, BorderLayout.NORTH);

        quickPanel = new JPanel();
        detailedPanel = new JPanel();
        quickPanel.setLayout(new BoxLayout(quickPanel, BoxLayout.Y_AXIS));
        detailedPanel.setLayout(new BoxLayout(detailedPanel, BoxLayout.Y_AXIS));

        tabs = new JTabbedPane();
        tabs.addTab("Quick", new JScrollPane(quickPanel));
        tabs.addTab("Detailed", new JScrollPane(detailedPanel));
        center.add(tabs, BorderLayout.CENTER);
        return center;
    }

    private JComponent buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.setBorder(new EmptyBorder(6, 10, 10, 10));

        overallBar = new JProgressBar(0, 100);
        overallBar.setStringPainted(true);
        moduleBar = new JProgressBar(0, 100);
        moduleBar.setStringPainted(true);
        statusLabel = new JLabel(" ");

        JPanel bars = new JPanel(new GridLayout(3, 1, 2, 2));
        bars.add(overallBar);
        bars.add(moduleBar);
        bars.add(statusLabel);

        logArea = new JTextArea(8, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JPanel top = new JPanel(new BorderLayout());
        top.add(bars, BorderLayout.NORTH);
        top.add(new JScrollPane(logArea), BorderLayout.CENTER);

        buildBtn = new JButton("Build");
        buildBtn.addActionListener(e -> startBuild());
        warnLabel = new JLabel(" ");
        warnLabel.setForeground(new Color(0xC0, 0x39, 0x2B));
        JPanel btnRow = new JPanel(new BorderLayout(8, 0));
        btnRow.add(warnLabel, BorderLayout.WEST);
        JPanel buildWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buildWrap.add(buildBtn);
        btnRow.add(buildWrap, BorderLayout.EAST);

        bottom.add(top, BorderLayout.CENTER);
        bottom.add(btnRow, BorderLayout.SOUTH);
        return bottom;
    }

    // ------------------------------------------------------------- behavior
    private void fillReleases() {
        releaseBox.removeAllItems();
        if (pack != null) for (String r : pack.releaseIds) releaseBox.addItem(r);
    }

    private void loadReleasesAsync() {
        setBusy(true);
        if (baseLabel != null) baseLabel.setText(russian ? "загрузка списка версий…" : "loading release list\u2026");
        new Thread(() -> {
            try {
                Pack np = patcher.loadPack();
                SwingUtilities.invokeLater(() -> {
                    pack = np;
                    fillReleases();
                    applyLocale();
                    refreshRelease();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    baseLabel.setText((russian ? "не удалось загрузить патчи: " : "could not load patches: ") + ex.getMessage());
                    setBusy(false);
                    JOptionPane.showMessageDialog(this,
                            (russian ? "Не удалось загрузить патчи:\n" : "Could not load patches:\n") + ex.getMessage(),
                            "WaterBuild", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "cw-releases").start();
    }

    private void refreshRelease() {
        if (pack == null) return;
        Object r = releaseBox.getSelectedItem();
        if (r == null) return;
        final String rel = (String) r;
        pack.select(rel);
        selected.clear();
        setBusy(true);
        baseLabel.setText((russian ? "загрузка " : "loading ") + rel + " ...");
        quickPanel.removeAll();
        quickPanel.revalidate();
        quickPanel.repaint();
        detailedPanel.removeAll();
        detailedPanel.revalidate();
        detailedPanel.repaint();
        new Thread(() -> {
            try {
                List<Unit> units = patcher.loadRelease(pack, rel, s -> {});
                SwingUtilities.invokeLater(() -> {
                    for (Unit u : units) selected.put(u.id, true);
                    rebuildTree();
                    setBusy(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    baseLabel.setText((russian ? "ошибка загрузки " : "load failed ") + rel + ": " + ex.getMessage());
                    setBusy(false);
                });
            }
        }, "cw-release").start();
    }

    private void setBusy(boolean busy) {
        if (buildBtn != null)
            buildBtn.setEnabled(!busy && pack != null && releaseBox.getSelectedItem() != null);
        if (refreshBtn != null) refreshBtn.setEnabled(!busy);
    }

    private void rebuildTree() {
        quickPanel.removeAll();
        detailedPanel.removeAll();
        baseLabel.setText(pack == null ? "no pack"
                : ("base: " + pack.baseName + "  sha256 " + shortSha(pack.baseSha)));
        if (pack == null) { quickPanel.revalidate(); updateWarnBanner(); return; }

        JLabel qh = new JLabel(russian ? "  Быстрый режим: только основные функции."
                                       : "  Quick mode: top-level features only.");
        qh.setForeground(Color.GRAY);
        qh.setAlignmentX(Component.LEFT_ALIGNMENT);
        quickPanel.add(qh);
        quickPanel.add(Box.createVerticalStrut(4));

        boolean anyQuick = false;
        for (Unit u : pack.units())
            if (u.category.equals("quick")) { anyQuick = true; quickPanel.add(row(u)); }
        if (!anyQuick) quickPanel.add(new JLabel("   (no quick modules)"));

        JLabel dh = new JLabel("  " + (russian ? "Все модули патча" : "Every patch module"));
        dh.setForeground(Color.GRAY);
        dh.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailedPanel.add(dh);
        detailedPanel.add(Box.createVerticalStrut(4));
        for (Unit u : pack.units()) detailedPanel.add(row(u));

        quickPanel.revalidate(); quickPanel.repaint();
        detailedPanel.revalidate(); detailedPanel.repaint();
        updateWarnBanner();
        applyTheme();
    }

    private boolean allSelected() {
        if (pack == null) return true;
        for (Unit u : pack.units())
            if (!Boolean.TRUE.equals(selected.get(u.id))) return false;
        return true;
    }

    private void updateWarnBanner() {
        if (warnLabel == null) return;
        if (allSelected()) warnLabel.setText(" ");
        else warnLabel.setText(russian
                ? "Внимание: при отключении модулей ядро может стать нестабильным — сборка на ваш риск."
                : "Warning: disabling modules may make the core unstable - build at your own risk.");
    }

    private void warnUnstable() {
        JOptionPane.showMessageDialog(this,
                russian ? "Внимание! Отключение модулей может сделать ядро нестабильным.\nСборка выполняется на ваш риск."
                        : "Warning! Disabling modules may make the core unstable.\nBuild at your own risk.",
                "WaterBuild", JOptionPane.WARNING_MESSAGE);
    }

    private JComponent row(Unit u) {
        JCheckBox cb = new JCheckBox(label(u));
        cb.setSelected(Boolean.TRUE.equals(selected.get(u.id)));
        cb.setEnabled(!u.locked);
        if (u.locked) cb.setForeground(Color.GRAY);
        cb.setToolTipText("<html><b>" + u.id + "</b> [" + u.category + "]<br/>" + u.desc(russian)
                + (u.depends.isEmpty() ? "" : "<br/><i>" + (russian ? "зависит" : "depends") + ": " + String.join(", ", u.depends) + "</i>")
                + (u.locked ? "<br/><i>" + (russian ? "нужно другим — отключить нельзя" : "required by others — cannot disable") + "</i>" : "")
                + "</html>");
        cb.addActionListener(e -> {
            selected.put(u.id, cb.isSelected());
            if (!cb.isSelected()) warnUnstable();
            rebuildTree();
        });

        JPanel r = new JPanel(new BorderLayout());
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        r.add(cb, BorderLayout.WEST);
        return r;
    }

    private void fillButtonText() { if (buildBtn != null) buildBtn.setText(russian ? "Собрать" : "Build"); }

    private void updateBuildEnabled() {
        if (buildBtn != null) buildBtn.setEnabled(pack != null && releaseBox.getSelectedItem() != null);
    }

    private void startBuild() {
        if (pack == null) return;
        final String rel = (String) releaseBox.getSelectedItem();
        final List<String> chosen = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : selected.entrySet())
            if (Boolean.TRUE.equals(e.getValue())) chosen.add(e.getKey());

        buildBtn.setEnabled(false);
        logArea.setText("");
        overallBar.setValue(0);
        moduleBar.setValue(0);

        new Thread(() -> {
            try {
                patcher.build(rel, chosen, this::log, this::progress);
                log("\n=== " + (russian ? "ГОТОВО" : "DONE") + ": " + rel + " ===");
                status(russian ? "готово" : "done");
                SwingUtilities.invokeLater(() -> {
                    buildBtn.setEnabled(true);
                    JOptionPane.showMessageDialog(this,
                            (russian ? "Готово" : "Done") + "\n" + patcher.outputJar(rel),
                            "WaterBuild", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                log("\n!!! " + (russian ? "ОШИБКА" : "ERROR") + ": " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    buildBtn.setEnabled(true);
                    JOptionPane.showMessageDialog(this, String.valueOf(ex.getMessage()),
                            "WaterBuild", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "cw-build").start();
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void progress(int o, int m, String stage) {
        SwingUtilities.invokeLater(() -> {
            overallBar.setValue(o);
            moduleBar.setValue(m);
            if (stage != null) statusLabel.setText(stage);
        });
    }

    private void status(String s) { SwingUtilities.invokeLater(() -> statusLabel.setText(s)); }

    private void applyLocale() {
        langBtn.setText(russian ? "EN" : "RU");
        releaseLabel.setText((russian ? "Версия" : "Release") + ":");
        if (themeBtn != null)
            themeBtn.setText(dark ? (russian ? "Светлая" : "Light")
                                  : (russian ? "Тёмная" : "Dark"));
        if (tabs != null) {
            tabs.setTitleAt(0, russian ? "Быстрый" : "Quick");
            tabs.setTitleAt(1, russian ? "Подробный" : "Detailed");
        }
        fillButtonText();
        if (pack != null) rebuildTree();
    }

    // --------------------------------------------------------------- theme
    private void applyTheme() {
        Color blue    = new Color(0x1E, 0x6F, 0xB4);
        Color panelBg = dark ? new Color(0x24, 0x24, 0x24) : new Color(0xF2, 0xF2, 0xF2);
        Color fg      = dark ? new Color(0xE8, 0xE8, 0xE8) : Color.BLACK;
        Color fieldBg = dark ? new Color(0x1A, 0x1A, 0x1A) : Color.WHITE;
        Color selBg   = dark ? new Color(0x3A, 0x3A, 0x3A) : new Color(0xE0, 0xE0, 0xE0);
        Color btnBg   = dark ? new Color(0x3C, 0x3C, 0x3C) : new Color(0xE6, 0xE6, 0xE6);

        UIManager.put("Panel.background", panelBg);
        UIManager.put("Panel.foreground", fg);
        UIManager.put("Label.foreground", fg);
        UIManager.put("Label.background", panelBg);
        UIManager.put("CheckBox.background", panelBg);
        UIManager.put("CheckBox.foreground", fg);
        UIManager.put("RadioButton.background", panelBg);
        UIManager.put("RadioButton.foreground", fg);
        UIManager.put("TextArea.background", fieldBg);
        UIManager.put("TextArea.foreground", fg);
        UIManager.put("TextPane.background", fieldBg);
        UIManager.put("TextPane.foreground", fg);
        UIManager.put("ComboBox.background", fieldBg);
        UIManager.put("ComboBox.foreground", fg);
        UIManager.put("ComboBox.selectionBackground", blue);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ScrollPane.background", panelBg);
        UIManager.put("Viewport.background", fieldBg);
        UIManager.put("Button.background", btnBg);
        UIManager.put("Button.foreground", fg);
        UIManager.put("Button.select", selBg);
        UIManager.put("ProgressBar.background", panelBg);
        UIManager.put("ProgressBar.foreground", blue);
        UIManager.put("ProgressBar.selectionForeground", fg);
        UIManager.put("ProgressBar.selectionBackground", fg);
        UIManager.put("TabbedPane.background", panelBg);
        UIManager.put("TabbedPane.foreground", fg);
        UIManager.put("TabbedPane.selected", selBg);
        for (String k : new String[] { "TabbedPane.contentAreaColor", "TabbedPane.shadow",
                "TabbedPane.darkShadow", "TabbedPane.highlight", "TabbedPane.light",
                "TabbedPane.focus", "TabbedPane.selectHighlight", "TabbedPane.borderHightlightColor" })
            UIManager.put(k, panelBg);

        if (appliedDark == null || appliedDark != dark) {
            SwingUtilities.updateComponentTreeUI(getContentPane());
            appliedDark = dark;
        }
        applyThemeTo(getContentPane(), panelBg, fg, fieldBg);
        restoreHeader(blue);
        if (warnLabel != null)
            warnLabel.setForeground(dark ? new Color(0xFF, 0x8A, 0x70) : new Color(0xC0, 0x39, 0x2B));
        repaint();
    }

    private void restoreHeader(Color blue) {
        if (topPanel != null) {
            topPanel.setBackground(blue);
            if (titleLabel != null) titleLabel.setForeground(Color.WHITE);
            if (releaseLabel != null) releaseLabel.setForeground(Color.WHITE);
        }
    }

    private void applyThemeTo(Component c, Color panelBg, Color fg, Color fieldBg) {
        if (c == topPanel) return; // keep the blue header as-is
        if (c instanceof JPanel) c.setBackground(panelBg);
        if (c instanceof JScrollPane) {
            ((JScrollPane) c).setBackground(panelBg);
            ((JScrollPane) c).getViewport().setBackground(fieldBg);
        }
        if (c instanceof JTextArea) { c.setBackground(fieldBg); c.setForeground(fg); }
        if (c instanceof JLabel) c.setForeground(fg);
        if (c instanceof JCheckBox) { c.setBackground(panelBg); if (c.isEnabled()) c.setForeground(fg); }
        if (c instanceof JComboBox) { c.setBackground(fieldBg); c.setForeground(fg); }
        if (c instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) c;
            tp.setBackground(panelBg); tp.setForeground(fg);
            for (int i = 0; i < tp.getTabCount(); i++) tp.setBackgroundAt(i, panelBg);
        }
        if (c instanceof Container)
            for (Component ch : ((Container) c).getComponents())
                applyThemeTo(ch, panelBg, fg, fieldBg);
    }

    private String label(Unit u) { return (russian ? u.nameRu : u.nameEn); }

    private static String shortSha(String s) {
        if (s == null || s.length() < 12) return String.valueOf(s);
        return s.substring(0, 12) + "...";
    }

    // =======================================================================
    //  Data model
    static class Unit {
        String id = "", nameEn = "", nameRu = "", descEn = "", descRu = "", category = "core", cw = "";
        boolean locked, optional;
        List<String> depends = new ArrayList<>();

        String desc(boolean ru) {
            String d = ru ? descRu : descEn;
            return d == null ? "" : d;
        }
    }

    static class Pack {
        Path dir;                                   // local dev pack folder, or null for GitHub
        String owner = "", repo = "", ref = "main"; // GitHub source
        String rawBase = "";                        // raw.githubusercontent base for cw fallback
        Path cacheDir;                              // per-release cache (GitHub mode)
        String baseName, baseUrl, baseSha;
        final List<String> releaseIds = new ArrayList<>();
        final Map<String, List<Unit>> byRelease = new LinkedHashMap<>();
        final Map<String, String> manifestUrls = new LinkedHashMap<>();
        final Map<String, Map<String, String>> assetUrls = new LinkedHashMap<>();
        String current;

        void select(String rel) { current = rel; }
        List<Unit> units() { return byRelease.getOrDefault(current, Collections.emptyList()); }
        Unit unit(String id) {
            for (Unit u : units()) if (u.id.equals(id)) return u;
            return null;
        }
    }

    // =======================================================================
    //  Engine
    // =======================================================================
    static class Patcher {
        final Path root;
        final Properties base = new Properties();
        final Map<String, Integer> hunkOffset = new HashMap<>();
        final Map<String, List<Hunk>> pendingHunks = new LinkedHashMap<>();
        int hunkSeq = 0;

        static class Hunk {
            final String path; final List<String> body; final int[] hn; final int seq;
            Hunk(String path, List<String> body, int[] hn, int seq) {
                this.path = path; this.body = body; this.hn = hn; this.seq = seq;
            }
        }

        static {
            if (System.getProperty("java.net.useSystemProxies") == null)
                System.setProperty("java.net.useSystemProxies", "true");
        }

        static final HttpClient HTTP = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(30)).build();

        interface Prog { void at(int overall, int module, String stage); }

        Patcher(Path root) {
            this.root = root;
            try (InputStream in = CraftWaterPatcher.class.getResourceAsStream("/base.properties")) {
                if (in != null) base.load(in);
            } catch (Exception ignore) {}
            Path local = root.resolve("base.properties");
            if (Files.exists(local)) {
                try (InputStream in = Files.newInputStream(local)) { base.load(in); } catch (Exception ignore) {}
            }
        }

        static Path findRoot() {
            String override = System.getProperty("cw.root");
            if (override != null && !override.isBlank()) return Paths.get(override).toAbsolutePath();
            try {
                Path p = Paths.get(CraftWaterPatcher.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(p)) return p.getParent();
                return p;
            } catch (Exception e) {
                return Paths.get("").toAbsolutePath();
            }
        }

        ImageIcon loadIcon() {
            try (InputStream in = openResource("/CW.png", "CW.png")) {
                if (in == null) return null;
                return new ImageIcon(in.readAllBytes());
            } catch (Exception e) {
                return null;
            }
        }

        InputStream openResource(String res, String localName) {
            try {
                InputStream in = CraftWaterPatcher.class.getResourceAsStream(res);
                if (in != null) return in;
                Path p = root.resolve(localName);
                if (Files.exists(p)) return Files.newInputStream(p);
            } catch (Exception ignore) {}
            return null;
        }

        // ---------------------------------------------------------- pack load
        Pack activePack;

        Pack loadPack() throws Exception {
            Path localPack = root.resolve("pack");
            if (Files.exists(localPack.resolve("pack.yml"))) {
                activePack = loadLocalPack(localPack);
            } else {
                activePack = loadPackFromGitHub();
            }
            return activePack;
        }

        Pack loadLocalPack(Path packDir) throws Exception {
            Map<String, Object> y = asMap(Yaml.parse(Files.readString(packDir.resolve("pack.yml"), StandardCharsets.UTF_8)));
            Pack pack = new Pack();
            pack.dir = packDir;
            Map<String, Object> b = asMap(y.get("base"));
            pack.baseName = base.getProperty("base.name", str(b.get("name")));
            pack.baseUrl = base.getProperty("base.url", str(b.get("url")));
            pack.baseSha = base.getProperty("base.sha256", str(b.get("sha256")));
            for (Object o : asList(y.get("releases"))) {
                Map<String, Object> r = asMap(o);
                String id = str(r.get("id"));
                if (id.isEmpty()) continue;
                pack.releaseIds.add(id);
                pack.byRelease.put(id, parseManifest(packDir.resolve(str(r.get("manifest")))));
            }
            return pack;
        }

        Pack loadPackFromGitHub() throws Exception {
            String owner = base.getProperty("pack.owner", "").trim();
            String repo = base.getProperty("pack.repo", "").trim();
            String ref = base.getProperty("pack.ref", "main").trim();
            if (owner.isEmpty() || repo.isEmpty())
                throw new IllegalStateException("pack.owner / pack.repo not set (see base.properties)");

            Pack pack = new Pack();
            pack.owner = owner;
            pack.repo = repo;
            pack.ref = ref;
            pack.rawBase = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + ref + "/";
            pack.baseName = base.getProperty("base.name", "CraftBukkit 1.2.5-R5.0");
            pack.baseUrl = base.getProperty("base.url");
            pack.baseSha = base.getProperty("base.sha256");
            pack.cacheDir = root.resolve("tools").resolve("pack").resolve(repo);
            Files.createDirectories(pack.cacheDir);

            Exception apiErr = null;
            try {
                loadIndexFromReleases(pack);
            } catch (Exception e) {
                apiErr = e;
            }
            if (pack.releaseIds.isEmpty()) {
                try {
                    loadIndexFromRaw(pack);
                } catch (Exception e) {
                    String msg = apiErr != null ? apiErr.getMessage() : e.getMessage();
                    throw new IllegalStateException("cannot read patch list from " + owner + "/" + repo
                            + (msg == null ? "" : ": " + msg));
                }
            }
            if (pack.releaseIds.isEmpty())
                throw new IllegalStateException("no patch releases found in " + owner + "/" + repo);
            return pack;
        }

        // GitHub Releases: tag = version, assets = manifest.yml + *.cw
        void loadIndexFromReleases(Pack pack) throws Exception {
            List<Map<String, Object>> rels = fetchReleases(pack.owner, pack.repo);
            rels.sort(Comparator.comparingLong((Map<String, Object> r) -> relTagOrder(str(r.get("tag_name")))));
            for (Map<String, Object> rel : rels) {
                String tag = str(rel.get("tag_name"));
                if (tag.isEmpty()) continue;
                String manifestUrl = null;
                Map<String, String> cwUrls = new LinkedHashMap<>();
                for (Object ao : asList(rel.get("assets"))) {
                    Map<String, Object> a = asMap(ao);
                    String name = str(a.get("name"));
                    String dl = str(a.get("browser_download_url"));
                    if (name.equalsIgnoreCase("manifest.yml") || name.equalsIgnoreCase("manifest.yaml"))
                        manifestUrl = dl;
                    else if (name.endsWith(".cw"))
                        cwUrls.put(name, dl);
                }
                if (manifestUrl == null) continue;
                pack.releaseIds.add(tag);
                pack.manifestUrls.put(tag, manifestUrl);
                pack.assetUrls.put(tag, cwUrls);
            }
        }

        // Fallback: a pack.yml committed in the repo tree (no Releases, no API limits)
        void loadIndexFromRaw(Pack pack) throws Exception {
            Path idx = pack.cacheDir.resolve("pack.yml");
            downloadTo(pack.rawBase + "pack.yml", idx);
            Map<String, Object> y = asMap(Yaml.parse(Files.readString(idx, StandardCharsets.UTF_8)));
            for (Object o : asList(y.get("releases"))) {
                Map<String, Object> r = asMap(o);
                String id = str(r.get("id"));
                if (id.isEmpty()) continue;
                String man = str(r.get("manifest")).replace('\\', '/');
                while (man.startsWith("./")) man = man.substring(2);
                pack.releaseIds.add(id);
                pack.manifestUrls.put(id, pack.rawBase + man);
                pack.assetUrls.put(id, new LinkedHashMap<>());
            }
            List<String> sorted = new ArrayList<>(pack.releaseIds);
            sorted.sort(Comparator.comparingLong(Patcher::relTagOrder));
            pack.releaseIds.clear();
            pack.releaseIds.addAll(sorted);
        }

        List<Map<String, Object>> fetchReleases(String owner, String repo) throws Exception {
            String token = base.getProperty("pack.token", "").trim();
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/releases?per_page=100";
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "WaterBuild")
                    .GET();
            if (!token.isEmpty()) rb.header("Authorization", "Bearer " + token);
            HttpResponse<String> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 403 || resp.statusCode() == 429)
                throw new IOException("GitHub API rate limit (HTTP " + resp.statusCode() + ")");
            if (resp.statusCode() / 100 != 2)
                throw new IOException("GitHub API HTTP " + resp.statusCode());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : asList(Json.parse(resp.body()))) out.add(asMap(o));
            return out;
        }

        // Download (once) the manifest + every .cw of one release and parse its units.
        List<Unit> loadRelease(Pack pack, String rel, Consumer<String> log) throws Exception {
            List<Unit> already = pack.byRelease.get(rel);
            if (already != null) return already;
            if (pack.dir != null)
                return parseManifest(pack.dir.resolve("releases").resolve(rel).resolve("manifest.yml"));

            Path rdir = pack.cacheDir.resolve(rel);
            Files.createDirectories(rdir);
            Path man = rdir.resolve("manifest.yml");
            if (!Files.exists(man)) {
                String manUrl = pack.manifestUrls.get(rel);
                if (manUrl == null) throw new IllegalStateException("manifest URL for " + rel + " unknown");
                log.accept("fetching " + rel + "/manifest.yml");
                downloadTo(manUrl, man);
            }
            Map<String, String> urls = pack.assetUrls.getOrDefault(rel, Collections.emptyMap());
            for (String cw : manifestCwNames(man)) {
                Path f = rdir.resolve(cw);
                if (Files.exists(f)) continue;
                String u = urls.get(cw);
                if (u == null) u = pack.rawBase + "releases/" + rel + "/" + cw;
                log.accept("fetching " + rel + "/" + cw);
                downloadTo(u, f);
            }
            List<Unit> units = parseManifest(man);
            pack.byRelease.put(rel, units);
            return units;
        }

        static List<String> manifestCwNames(Path man) throws Exception {
            Map<String, Object> y = asMap(Yaml.parse(Files.readString(man, StandardCharsets.UTF_8)));
            List<String> out = new ArrayList<>();
            for (Object o : asList(y.get("units"))) {
                String cw = str(asMap(o).get("cw"));
                if (!cw.isEmpty()) out.add(cw);
            }
            return out;
        }

        static long relTagOrder(String tag) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(tag == null ? "" : tag);
            long major = 0, minor = 0;
            int n = 0;
            while (m.find()) {
                long v = Long.parseLong(m.group(1));
                if (n == 0) major = v;
                else if (n == 1) minor = v;
                n++;
            }
            return major * 1000L + minor;
        }

        void downloadTo(String url, Path out) throws Exception {
            Files.createDirectories(out.getParent());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "WaterBuild")
                    .GET().build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2)
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            try (InputStream in = resp.body()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        List<Unit> parseManifest(Path man) throws Exception {
            Map<String, Object> y = asMap(Yaml.parse(Files.readString(man, StandardCharsets.UTF_8)));
            List<Unit> out = new ArrayList<>();
            Path dir = man.getParent();
            for (Object o : asList(y.get("units"))) {
                Map<String, Object> m = asMap(o);
                Unit u = new Unit();
                u.id = str(m.get("id"));
                u.cw = str(m.get("cw"));
                u.category = str(m.getOrDefault("category", "core"));
                u.locked = asBool(m.get("locked"));
                Unit meta = readCwMeta(dir.resolve(str(m.get("cw"))));
                u.nameEn = meta.nameEn;
                u.nameRu = meta.nameRu;
                u.descEn = meta.descEn;
                u.descRu = meta.descRu;
                u.optional = meta.optional;
                for (Object d : asList(m.get("depends"))) u.depends.add(str(d));
                out.add(u);
            }
            return out;
        }

        Unit readCwMeta(Path cw) {
            Unit u = new Unit();
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(cw))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.getName().replace('\\', '/').equals("patch.yml")) {
                        Map<String, Object> y = asMap(Yaml.parse(new String(zin.readAllBytes(), StandardCharsets.UTF_8)));
                        u.id = str(y.get("id"));
                        u.nameEn = str(y.get("name_en"));
                        u.nameRu = str(y.get("name_ru"));
                        u.descEn = str(y.get("desc_en"));
                        u.descRu = str(y.get("desc_ru"));
                        u.category = str(y.getOrDefault("category", "core"));
                        u.optional = asBool(y.get("optional"));
                        break;
                    }
                }
            } catch (Exception ignore) {}
            return u;
        }

        // ----------------------------------------------------------- paths
        Path buildDir(String rel) { return root.resolve("src").resolve("CraftWater").resolve(rel); }
        Path outputJar(String rel) {
            return root.resolve("CraftWater-" + rel + ".jar");
        }

        // -------------------------------------------------------- build run
        void build(String rel, List<String> chosen, Consumer<String> log, Prog prog) throws Exception {
            log.accept("CraftWater build: " + rel);
            log.accept("root: " + root);
            checkJava(log);
            prog.at(2, 0, "prepare");

            Path tools = root.resolve("tools");
            Files.createDirectories(tools);

            prog.at(5, 0, "base");
            Path baseZip = downloadBase(log);

            prog.at(12, 0, "dependencies");
            Path depBukkit = downloadDependency("bukkit", log);
            Path depMc = downloadDependency("minecraft", log);

            Path dir = buildDir(rel);
            prog.at(18, 0, "extract base");
            deleteRec(dir);
            Files.createDirectories(dir);
            try (InputStream in = Files.newInputStream(baseZip)) {
                unzip(in, dir, base.getProperty("base.root", ""));
            }
            log.accept("base extracted -> " + dir);

            prog.at(28, 0, "maven");
            Path m2 = root.resolve(".m2");
            Files.createDirectories(m2);
            Path mvn = ensureMaven(root.resolve("tools"), log);

            prog.at(34, 0, "install deps");
            installFile(mvn, m2, depBukkit, "org.bukkit", "bukkit", base.getProperty("dep.bukkit.version"), log);
            installFile(mvn, m2, depMc, "org.bukkit", "minecraft-server", base.getProperty("dep.minecraft.version"), log);

            Pack pack = (activePack != null && activePack.releaseIds.contains(rel)) ? activePack : loadPack();
            pack.select(rel);
            loadRelease(pack, rel, log);
            int total = Math.max(1, chosen.size());

            prog.at(42, 0, "verify patches");
            List<String> ordered = orderForApply(pack.units(), chosen);
            for (Unit u : pack.units()) {
                if (!chosen.contains(u.id)) continue;
                verifyBaseFiles(cwFile(pack, rel, u.id), dir);
            }

            prog.at(50, 0, "apply patches");
            hunkOffset.clear();
            pendingHunks.clear();
            hunkSeq = 0;
            int i = 0;
            for (String id : ordered) {
                applyCw(cwFile(pack, rel, id), dir, log);
                i++;
                prog.at(50 + (int) (i * 20.0 / total), (int) (i * 100.0 / total), "applied " + id);
            }
            flushHunks(dir, hunkOffset, log);

            prog.at(74, 0, "mvn package");
            mvnPackage(mvn, m2, dir, log);

            prog.at(96, 0, "collect");
            Path jar = pickJar(dir);
            Path out = outputJar(rel);
            Files.copy(jar, out, StandardCopyOption.REPLACE_EXISTING);
            log.accept("built jar: " + out + "  (" + Files.size(out) + " bytes)");
            prog.at(100, 100, "done");
        }

        Path cwFile(Pack pack, String rel, String id) {
            String fn = id + ".cw";
            Unit u = pack.unit(id);
            if (u != null && u.cw != null && !u.cw.isEmpty()) fn = u.cw;
            if (pack.dir != null) return pack.dir.resolve("releases").resolve(rel).resolve(fn);
            return pack.cacheDir.resolve(rel).resolve(fn);
        }

        // -------------------------------------------------------- downloads
        Path baseZipPath() {
            return root.resolve("tools").resolve("base")
                    .resolve("base-" + base.getProperty("base.version", "1.2.5") + ".zip");
        }

        Path downloadBase(Consumer<String> log) throws Exception {
            String url = base.getProperty("base.url");
            String sha = base.getProperty("base.sha256");
            Path out = baseZipPath();
            if (Files.exists(out) && sha != null && sha.equalsIgnoreCase(sha256(out))) {
                log.accept("base cached, sha256 ok");
            } else {
                log.accept("downloading base: " + url);
                download(url, out, log);
            }
            String got = sha256(out);
            if (sha != null && (got == null || !got.equalsIgnoreCase(sha)))
                throw new IllegalStateException("base sha256 mismatch\n expected " + sha + "\n got      " + got);
            return out;
        }

        Path downloadDependency(String which, Consumer<String> log) throws Exception {
            String url = base.getProperty("dep." + which + ".url");
            Path out = root.resolve("tools").resolve("deps").resolve(url.substring(url.lastIndexOf('/') + 1));
            if (Files.exists(out) && Files.size(out) > 0) {
                log.accept("dep cached: " + out.getFileName());
                return out;
            }
            log.accept("downloading dep: " + url);
            download(url, out, log);
            return out;
        }

        void download(String url, Path out, Consumer<String> log) throws Exception {
            Files.createDirectories(out.getParent());
            HttpResponse<InputStream> resp = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2)
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            try (InputStream in = resp.body()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            log.accept("  saved " + out.getFileName() + " (" + Files.size(out) + " bytes)");
        }

        Path ensureMaven(Path tools, Consumer<String> log) throws Exception {
            String envMvn = System.getenv("CW_MVN");
            if (envMvn != null && !envMvn.isBlank() && Files.exists(Paths.get(envMvn))) {
                log.accept("using CW_MVN: " + envMvn);
                return Paths.get(envMvn);
            }
            final String ver = "3.9.11";
            Path home = tools.resolve("apache-maven-" + ver);
            Path mvn = home.resolve("bin").resolve("mvn.cmd");
            Path mvnUnix = home.resolve("bin").resolve("mvn");
            if (Files.exists(mvn)) return mvn;
            if (Files.exists(mvnUnix)) return mvnUnix;

            Path zip = tools.resolve("apache-maven-" + ver + "-bin.zip");
            if (!Files.exists(zip)) {
                String[] urls = {
                        "https://archive.apache.org/dist/maven/maven-3/" + ver
                                + "/binaries/apache-maven-" + ver + "-bin.zip",
                        "https://dlcdn.apache.org/maven/maven-3/" + ver
                                + "/binaries/apache-maven-" + ver + "-bin.zip",
                        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/"
                                + ver + "/apache-maven-" + ver + "-bin.zip"
                };
                log.accept("downloading Maven " + ver + " ...");
                Exception last = null;
                boolean got = false;
                for (String url : urls) {
                    try { download(url, zip, log); got = true; break; }
                    catch (Exception e) { last = e; log.accept("mirror failed: " + url); }
                }
                if (!got) throw new IllegalStateException("cannot download Maven " + ver
                        + (last != null ? " (" + last.getMessage() + ")" : ""));
            }
            log.accept("extracting Maven ...");
            try (InputStream in = Files.newInputStream(zip)) { unzip(in, tools, null); }
            if (Files.exists(mvn)) return mvn;
            if (Files.exists(mvnUnix)) return mvnUnix;
            throw new IllegalStateException("Maven extracted but mvn not found under " + home);
        }

        List<String> mvnArgs() {
            List<String> a = new ArrayList<>();
            // Maven Resolver: fail fast instead of hanging on an unresponsive repo.
            a.add("-Daether.connector.connectTimeout=10000");
            a.add("-Daether.connector.requestTimeout=30000");
            a.add("-Daether.connector.leaseTimeout=15000");
            a.add("-Dmaven.wagon.http.connectionTimeout=10000");
            a.add("-Dmaven.wagon.http.readTimeout=30000");
            a.add("-Dmaven.wagon.rto=30000");
            // Let Maven's JVM honor the OS proxy settings.
            a.add("-Djava.net.useSystemProxies=true");
            for (String k : new String[]{"http.proxyHost", "http.proxyPort",
                    "https.proxyHost", "https.proxyPort", "http.nonProxyHosts"}) {
                String v = System.getProperty(k);
                if (v != null && !v.isEmpty()) a.add("-D" + k + "=" + v);
            }
            try {
                ProxySelector ps = ProxySelector.getDefault();
                if (ps != null) {
                    for (Proxy p : ps.select(URI.create("https://repo.maven.apache.org/maven2/"))) {
                        if (p.type() == Proxy.Type.HTTP && p.address() instanceof InetSocketAddress isa) {
                            String host = isa.getHostString();
                            if (host != null && !host.isEmpty() && isa.getPort() > 0) {
                                a.add("-Dhttp.proxyHost=" + host);
                                a.add("-Dhttp.proxyPort=" + isa.getPort());
                                a.add("-Dhttps.proxyHost=" + host);
                                a.add("-Dhttps.proxyPort=" + isa.getPort());
                            }
                            break;
                        }
                    }
                }
            } catch (Exception ignore) {}
            return a;
        }

        void installFile(Path mvn, Path m2, Path jar, String g, String a, String v,
                         Consumer<String> log) throws Exception {
            log.accept("install " + g + ":" + a + ":" + v);
            List<String> cmd = new ArrayList<>(mvnArgs());
            cmd.add(mvn.toString());
            cmd.add("-q");
            cmd.add("-Dmaven.repo.local=" + m2);
            cmd.add("install:install-file");
            cmd.add("-Dfile=" + jar);
            cmd.add("-DgroupId=" + g);
            cmd.add("-DartifactId=" + a);
            cmd.add("-Dversion=" + v);
            cmd.add("-Dpackaging=jar");
            run(log, root, cmd.toArray(new String[0]));
        }

        void mvnPackage(Path mvn, Path m2, Path dir, Consumer<String> log) throws Exception {
            List<String> cmd = new ArrayList<>(mvnArgs());
            cmd.add(mvn.toString());
            cmd.add("-B");
            cmd.add("-Dmaven.repo.local=" + m2);
            cmd.add("-DskipTests");
            cmd.add("clean");
            cmd.add("package");
            run(log, dir, cmd.toArray(new String[0]));
        }

        void run(Consumer<String> log, Path cwd, String... cmd) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (cwd != null) pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) pb.environment().put("JAVA_HOME", javaHome);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log.accept(line);
            }
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("command failed (" + code + "): " + String.join(" ", cmd));
        }

        void checkJava(Consumer<String> log) {
            String v = System.getProperty("java.version");
            log.accept("Java " + v);
            int major = parseMajor(v);
            if (major < 21)
                throw new IllegalStateException("Java 21+ is required for the build (found " + v + ")");
        }

        static int parseMajor(String v) {
            try {
                if (v.startsWith("1.")) return Integer.parseInt(v.substring(2, 3));
                int dot = v.indexOf('.');
                return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
            } catch (Exception e) { return 0; }
        }

        // ------------------------------------------------------- cw handling
        void verifyBaseFiles(Path cw, Path dir) throws Exception {
            Map<String, String> baseFiles = new LinkedHashMap<>();
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(cw))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.getName().replace('\\', '/').equals("patch.yml")) {
                        Map<String, Object> y = asMap(Yaml.parse(new String(zin.readAllBytes(), StandardCharsets.UTF_8)));
                        Map<String, Object> bf = asMap(y.get("baseFiles"));
                        for (Map.Entry<String, Object> en : bf.entrySet())
                            baseFiles.put(en.getKey(), str(en.getValue()));
                        break;
                    }
                }
            }
            for (Map.Entry<String, String> en : baseFiles.entrySet()) {
                Path file = dir.resolve(en.getKey());
                if (!Files.exists(file))
                    throw new IllegalStateException("base file missing: " + en.getKey() + " (wrong base version?)");
                String got = sha256(file);
                if (got == null || !got.equalsIgnoreCase(en.getValue()))
                    throw new IllegalStateException("base file hash mismatch: " + en.getKey()
                            + "\n expected " + en.getValue() + "\n got      " + got);
            }
        }

        void applyCw(Path cw, Path dir, Consumer<String> log) throws Exception {
            String diff = null;
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(cw))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName().replace('\\', '/');
                    if (name.equals("patch.diff")) diff = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                    else if (name.startsWith("files/")) files.put(name.substring("files/".length()), zin.readAllBytes());
                }
            }
            if (diff != null && !diff.isBlank()) collectUnifiedDiff(diff);
            for (Map.Entry<String, byte[]> en : files.entrySet()) {
                Path f = dir.resolve(en.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, en.getValue());
            }
            log.accept("applied " + cw.getFileName());
        }

        void collectUnifiedDiff(String diff) {
            String[] lines = diff.split("\n", -1);
            int i = 0;
            while (i < lines.length) {
                if (!lines[i].startsWith("diff --git ")) { i++; continue; }
                i++;
                String newPath = null, oldPath = null;
                while (i < lines.length && !lines[i].startsWith("@@")) {
                    String h = lines[i];
                    if (h.startsWith("+++ ")) newPath = cleanHeaderPath(h.substring(4));
                    else if (h.startsWith("--- ")) oldPath = cleanHeaderPath(h.substring(4));
                    i++;
                }
                if (i >= lines.length || !lines[i].startsWith("@@")) continue;
                int[] hn = parseHunkHeader(lines[i]);
                i++;
                List<String> body = new ArrayList<>();
                while (i < lines.length) {
                    String b = lines[i];
                    if (b.isEmpty()) break;
                    char c = b.charAt(0);
                    if (c != ' ' && c != '+' && c != '-' && c != '\\') break;
                    body.add(b);
                    i++;
                }
                if (newPath == null || newPath.equals("/dev/null")) continue;
                pendingHunks.computeIfAbsent(newPath, k -> new ArrayList<>())
                        .add(new Hunk(newPath, body, hn, hunkSeq++));
            }
        }

        // Apply all queued hunks grouped by file in document order (by old-file line),
        // so independently-authored sub-hunks that anchor at the same location keep order.
        void flushHunks(Path dir, Map<String, Integer> delta, Consumer<String> log) throws IOException {
            for (Map.Entry<String, List<Hunk>> en : pendingHunks.entrySet()) {
                List<Hunk> hs = en.getValue();
                hs.sort(Comparator.comparingInt((Hunk h) -> h.hn[0])
                        .thenComparingInt(h -> h.hn[2])
                        .thenComparingInt(h -> h.seq));
                for (Hunk h : hs) applyHunk(dir, h.path, h.body, h.hn, delta);
            }
            pendingHunks.clear();
        }

        static int[] parseHunkHeader(String s) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
                    .matcher(s);
            if (m.find()) {
                int os = Integer.parseInt(m.group(1));
                int oc = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                int ns = Integer.parseInt(m.group(3));
                int nc = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
                return new int[]{os, oc, ns, nc};
            }
            return new int[]{0, 0, 0, 0};
        }

        static void applyHunk(Path dir, String newPath, List<String> body, int[] hn,
                              Map<String, Integer> delta) throws IOException {
            if (newPath == null || newPath.equals("/dev/null")) return;
            String path = newPath;
            if (path.startsWith("b/") || path.startsWith("a/")) path = path.substring(2);
            Path f = dir.resolve(path);

            List<String> file = new ArrayList<>();
            if (Files.exists(f)) {
                String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                file.addAll(Arrays.asList(s.split("\n", -1)));
            }

            List<String> oldBlock = new ArrayList<>();
            List<String> context = new ArrayList<>();
            boolean newNoNewline = false;
            char prev = 0;
            for (String b : body) {
                char c = b.charAt(0);
                if (c == '\\') {
                    if (prev == '+' || prev == ' ') newNoNewline = true;
                    continue;
                }
                prev = c;
                String content = b.substring(1);
                if (c == ' ' || c == '-') oldBlock.add(content);
                if (c == ' ') context.add(content);
            }

            int d = delta.getOrDefault(path, 0);
            int expected = Math.max(0, hn[0] - 1 + d);

            int pos = nearestMatch(file, oldBlock, expected);
            int oldLen = oldBlock.size();
            if (pos < 0 && !context.isEmpty()) {
                pos = nearestMatch(file, context, expected);
                if (pos >= 0) oldLen = context.size();
            }
            if (pos < 0)
                throw new IOException("hunk context not found in " + path);

            List<String> result = new ArrayList<>(file.subList(0, pos));
            for (String b : body) {
                char c = b.charAt(0);
                if (c == '\\') continue;
                if (c == ' ') result.add(b.substring(1));
                else if (c == '+') result.add(b.substring(1));
            }
            result.addAll(file.subList(pos + oldLen, file.size()));

            if (newNoNewline && !result.isEmpty() && result.get(result.size() - 1).isEmpty())
                result.remove(result.size() - 1);

            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < result.size(); k++) {
                sb.append(result.get(k));
                if (k < result.size() - 1) sb.append('\n');
            }
            Files.createDirectories(f.getParent());
            Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));

            delta.put(path, d + (hn[3] - hn[1]));
        }

        static int nearestMatch(List<String> file, List<String> block, int expected) {
            if (block.isEmpty()) return Math.max(0, Math.min(expected, file.size()));
            int best = -1, bestDist = Integer.MAX_VALUE;
            outer:
            for (int s = 0; s + block.size() <= file.size(); s++) {
                for (int k = 0; k < block.size(); k++)
                    if (!file.get(s + k).equals(block.get(k))) continue outer;
                int dist = Math.abs(s - expected);
                if (dist < bestDist) { bestDist = dist; best = s; }
            }
            return best;
        }

        static String cleanHeaderPath(String p) {
            p = p.trim();
            int tab = p.indexOf('\t');
            if (tab >= 0) p = p.substring(0, tab);
            return p;
        }

        // -------------------------------------------------------- helpers
        List<String> orderForApply(List<Unit> units, List<String> chosen) {
            Set<String> set = new HashSet<>(chosen);
            List<String> out = new ArrayList<>();
            Set<String> placed = new HashSet<>();
            boolean progress = true;
            while (progress) {
                progress = false;
                for (Unit u : units) {
                    if (!set.contains(u.id) || placed.contains(u.id)) continue;
                    boolean ready = true;
                    for (String d : u.depends) if (set.contains(d) && !placed.contains(d)) ready = false;
                    if (ready) { out.add(u.id); placed.add(u.id); progress = true; }
                }
            }
            for (String c : chosen) if (!placed.contains(c)) out.add(c);
            return out;
        }

        Path pickJar(Path dir) throws IOException {
            Path target = dir.resolve("target");
            if (!Files.exists(target)) throw new IOException("no target/ produced by Maven");
            Path best = null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(target, "*.jar")) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (n.startsWith("original-")) continue;
                    if (n.endsWith("-sources.jar") || n.endsWith("-javadoc.jar")) continue;
                    best = p;
                }
            }
            if (best == null) throw new IOException("no jar found in target/");
            return best;
        }

        // ------------------------------------------------------------ tests
        boolean selfTest(String rel, Path expectedTree, Consumer<String> log) {
            try {
                Pack pack = loadPack();
                pack.select(rel);
                loadRelease(pack, rel, log);
                List<String> all = new ArrayList<>();
                for (Unit u : pack.units()) all.add(u.id);

                Path work = Files.createTempDirectory("cw-selftest");
                Path dir = work.resolve("tree");
                Files.createDirectories(dir);
                try (InputStream in = Files.newInputStream(baseZipPath())) {
                    unzip(in, dir, base.getProperty("base.root", ""));
                }
                for (Unit u : pack.units()) verifyBaseFiles(cwFile(pack, rel, u.id), dir);
                hunkOffset.clear();
                pendingHunks.clear();
                hunkSeq = 0;
                for (String id : orderForApply(pack.units(), all)) applyCw(cwFile(pack, rel, id), dir, log);
                flushHunks(dir, hunkOffset, log);
                log.accept("apply ok -> " + dir);

                if (expectedTree != null) {
                    int[] diff = compareTrees(dir.resolve("src"), expectedTree.resolve("src"));
                    boolean pomSame = Files.exists(dir.resolve("pom.xml"))
                            && sha256Norm(dir.resolve("pom.xml")).equals(sha256Norm(expectedTree.resolve("pom.xml")));
                    log.accept("pom identical: " + pomSame);
                    log.accept("diff files vs expected: " + diff[0]);
                    return diff[0] == 0 && pomSame;
                }
                return true;
            } catch (Exception e) {
                log.accept("selftest FAILED: " + e);
                return false;
            }
        }

        static int[] compareTrees(Path a, Path b) throws IOException {
            Map<String, String> ha = hashTree(a), hb = hashTree(b);
            int diff = 0;
            for (Map.Entry<String, String> e : hb.entrySet())
                if (!e.getValue().equals(ha.get(e.getKey()))) { diff++; System.out.println("DIFF " + e.getKey()); }
            for (String k : ha.keySet()) if (!hb.containsKey(k)) { diff++; System.out.println("ONLY-A " + k); }
            return new int[]{diff};
        }

        static Map<String, String> hashTree(Path dir) throws IOException {
            Map<String, String> m = new TreeMap<>();
            if (!Files.exists(dir)) return m;
            Files.walk(dir).filter(Files::isRegularFile).forEach(p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.endsWith("/.java") || rel.endsWith("desktop.ini")) return;
                try { m.put(rel, sha256Norm(p)); } catch (IOException ignore) {}
            });
            return m;
        }

        static String sha256Norm(Path p) throws IOException {
            byte[] raw = Files.readAllBytes(p);
            int n = 0;
            for (byte b : raw) if (b != '\r') raw[n++] = b;
            byte[] clean = java.util.Arrays.copyOf(raw, n);
            try {
                byte[] dig = MessageDigest.getInstance("SHA-256").digest(clean);
                StringBuilder sb = new StringBuilder();
                for (byte b : dig) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }

        String sha256(Path p) { return sha256Static(p); }

        static String sha256Static(Path p) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(Files.readAllBytes(p));
                StringBuilder sb = new StringBuilder();
                for (byte x : d) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) { return null; }
        }

        static void unzip(InputStream in, Path dest, String stripPrefix) throws IOException {
            ZipInputStream zin = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (stripPrefix != null && !stripPrefix.isEmpty()) {
                    String pre = stripPrefix.endsWith("/") ? stripPrefix : stripPrefix + "/";
                    if (name.equals(stripPrefix) || name.equals(pre)) continue;
                    if (name.startsWith(pre)) name = name.substring(pre.length());
                }
                if (name.isEmpty()) continue;
                Path out = dest.resolve(name);
                if (!out.normalize().startsWith(dest.normalize())) throw new IOException("zip slip: " + name);
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        static void deleteRec(Path p) throws IOException {
            if (!Files.exists(p)) return;
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (IOException ignore) {}
            });
        }

        // ------------------------------------------------------ yaml helpers
        @SuppressWarnings("unchecked")
        static Map<String, Object> asMap(Object o) {
            return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
        }

        @SuppressWarnings("unchecked")
        static List<Object> asList(Object o) {
            return (o instanceof List) ? (List<Object>) o : new ArrayList<>();
        }

        static String str(Object o) { return o == null ? "" : String.valueOf(o); }

        static boolean asBool(Object o) {
            return o != null && ("true".equalsIgnoreCase(String.valueOf(o)) || Boolean.TRUE.equals(o));
        }
    }

    // =======================================================================
    //  Minimal YAML subset parser (maps, lists, scalars, inline [a, b])
    // =======================================================================
    static final class Yaml {
        static Object parse(String text) {
            List<Object[]> ls = new ArrayList<>(); // {indent, content}
            for (String raw : text.split("\n", -1)) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                ls.add(new Object[]{indent, trimmed});
            }
            int[] idx = {0};
            return idx[0] < ls.size() ? parseNode(ls, idx, (int) ls.get(0)[0]) : new LinkedHashMap<String, Object>();
        }

        @SuppressWarnings("unchecked")
        static Object parseNode(List<Object[]> ls, int[] idx, int indent) {
            if (idx[0] >= ls.size()) return new LinkedHashMap<String, Object>();
            String content = (String) ls.get(idx[0])[1];
            if (content.startsWith("- ") || content.equals("-")) return parseList(ls, idx, indent);
            return parseMap(ls, idx, indent);
        }

        static List<Object> parseList(List<Object[]> ls, int[] idx, int indent) {
            List<Object> out = new ArrayList<>();
            while (idx[0] < ls.size()) {
                int ind = (int) ls.get(idx[0])[0];
                String content = (String) ls.get(idx[0])[1];
                if (ind != indent || !(content.startsWith("- ") || content.equals("-"))) break;
                String rest = content.equals("-") ? "" : content.substring(2).trim();
                idx[0]++;
                if (rest.isEmpty()) {
                    out.add(parseNode(ls, idx, nextIndent(ls, idx[0])));
                } else if (looksLikeKey(rest)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    int ci = rest.indexOf(':');
                    String k = rest.substring(0, ci).trim();
                    String v = rest.substring(ci + 1).trim();
                    if (v.isEmpty()) item.put(k, parseNode(ls, idx, nextIndent(ls, idx[0])));
                    else item.put(k, scalar(v));
                    while (idx[0] < ls.size() && (int) ls.get(idx[0])[0] > indent) {
                        int ci2 = (int) ls.get(idx[0])[0];
                        Map<String, Object> more = parseMap(ls, idx, ci2);
                        item.putAll(more);
                    }
                    out.add(item);
                } else {
                    out.add(scalar(rest));
                }
            }
            return out;
        }

        static Map<String, Object> parseMap(List<Object[]> ls, int[] idx, int indent) {
            Map<String, Object> m = new LinkedHashMap<>();
            while (idx[0] < ls.size()) {
                int ind = (int) ls.get(idx[0])[0];
                String content = (String) ls.get(idx[0])[1];
                if (ind != indent || content.startsWith("- ")) break;
                int ci = content.indexOf(':');
                if (ci < 0) { idx[0]++; continue; }
                String key = content.substring(0, ci).trim();
                String v = content.substring(ci + 1).trim();
                idx[0]++;
                if (v.isEmpty()) {
                    if (idx[0] < ls.size() && (int) ls.get(idx[0])[0] > indent)
                        m.put(key, parseNode(ls, idx, (int) ls.get(idx[0])[0]));
                    else
                        m.put(key, null);
                } else {
                    m.put(key, scalar(v));
                }
            }
            return m;
        }

        static int nextIndent(List<Object[]> ls, int idx) {
            return idx < ls.size() ? (int) ls.get(idx)[0] : 0;
        }

        static boolean looksLikeKey(String s) {
            int ci = s.indexOf(':');
            if (ci <= 0) return false;
            String key = s.substring(0, ci).trim();
            return key.matches("[A-Za-z0-9_.\\-]+");
        }

        static Object scalar(String v) {
            v = v.trim();
            if (v.startsWith("[") && v.endsWith("]")) {
                List<Object> list = new ArrayList<>();
                String inner = v.substring(1, v.length() - 1).trim();
                if (!inner.isEmpty())
                    for (String part : inner.split(",")) list.add(scalar(part.trim()));
                return list;
            }
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")) && v.length() >= 2)
                return v.substring(1, v.length() - 1);
            return v;
        }
    }

    // =======================================================================
    //  Minimal JSON parser (enough for the GitHub releases API response)
    // =======================================================================
    static final class Json {
        static Object parse(String s) {
            P p = new P(s);
            p.ws();
            return p.value();
        }

        static final class P {
            final String s;
            int i = 0;
            P(String s) { this.s = s; }

            void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

            Object value() {
                ws();
                if (i >= s.length()) return null;
                char c = s.charAt(i);
                switch (c) {
                    case '{': return obj();
                    case '[': return arr();
                    case '"': return str();
                    case 't': i += 4; return Boolean.TRUE;
                    case 'f': i += 5; return Boolean.FALSE;
                    case 'n': i += 4; return null;
                    default: return num();
                }
            }

            Map<String, Object> obj() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                ws();
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                while (i < s.length()) {
                    ws();
                    String k = str();
                    ws();
                    if (i < s.length() && s.charAt(i) == ':') i++;
                    m.put(k, value());
                    ws();
                    if (i >= s.length()) break;
                    if (s.charAt(i++) == '}') break;
                }
                return m;
            }

            List<Object> arr() {
                List<Object> l = new ArrayList<>();
                i++;
                ws();
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                while (i < s.length()) {
                    l.add(value());
                    ws();
                    if (i >= s.length()) break;
                    if (s.charAt(i++) == ']') break;
                }
                return l;
            }

            String str() {
                StringBuilder b = new StringBuilder();
                if (i < s.length() && s.charAt(i) == '"') i++;
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\' && i < s.length()) {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n': b.append('\n'); break;
                            case 't': b.append('\t'); break;
                            case 'r': b.append('\r'); break;
                            case 'b': b.append('\b'); break;
                            case 'f': b.append('\f'); break;
                            case 'u':
                                if (i + 4 <= s.length()) {
                                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                    i += 4;
                                }
                                break;
                            default: b.append(e);
                        }
                    } else {
                        b.append(c);
                    }
                }
                return b.toString();
            }

            Object num() {
                int st = i;
                while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
                return s.substring(st, i);
            }
        }
    }
}
