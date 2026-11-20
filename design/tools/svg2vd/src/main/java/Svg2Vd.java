import com.android.ide.common.vectordrawable.Svg2Vector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Converts the icon SVGs exported from Figma into Android VectorDrawables.
 *
 * <p>Input: {@code <svgDir>/<name>_line.svg} (24 x 24 outline) and {@code <svgDir>/<name>_3d.svg}
 * (32 x 32 artwork with gradients). Output: {@code <outDir>/ic_<name>_line.xml} and
 * {@code <outDir>/ic_<name>_3d.xml}.
 *
 * <p>The conversion itself is done by Google's {@link Svg2Vector} (the converter behind Android
 * Studio's "Vector Asset" import). This class only adds what that converter does not do:
 *
 * <ol>
 *   <li>Line icons: the single stroke colour is written as {@code #FFFFFFFF} so the app can tint
 *       the icon.
 *   <li>3D icons: a VectorDrawable radial gradient is always a circle, and Svg2Vector silently
 *       turns an elliptical SVG gradient ({@code gradientTransform} with unequal scale) into a
 *       circle. To keep the ellipse, the path is wrapped in a {@code <group>} that squashes (and
 *       rotates) it around the gradient centre, and the path data is pre-transformed with the
 *       inverse so the outline does not move.
 *   <li>Checks: size and viewport, number of paths, gradients and opacities, well-formed XML.
 * </ol>
 *
 * <p>Exits with status 1 if the converter reports anything or a check fails.
 */
public final class Svg2Vd {
    private static final String WHITE = "#FFFFFFFF";
    private static final String TRANSPARENT = "#00000000";

    private static final Pattern PATH_TOKEN =
            Pattern.compile("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?");
    private static final String NUM = "([-+]?[0-9.]+(?:[eE][-+]?[0-9]+)?)";
    /** The only gradientTransform form Figma writes: translate(x y) [rotate(a)] scale(sx [sy]). */
    private static final Pattern GRADIENT_TRANSFORM =
            Pattern.compile(
                    "translate\\(" + NUM + "[ ,]+" + NUM + "\\)\\s*"
                            + "(?:rotate\\(" + NUM + "\\)\\s*)?"
                            + "scale\\(" + NUM + "(?:[ ,]+" + NUM + ")?\\)");
    private static final Pattern URL_REF = Pattern.compile("url\\(#([^)]+)\\)");
    private static final Pattern COLOR_ATTR = Pattern.compile("android:(strokeColor|fillColor)=\"([^\"]*)\"");
    private static final Pattern PATH_DATA_ATTR = Pattern.compile("android:pathData=\"[^\"]*\"");

    private final List<String> problems = new ArrayList<>();
    private int wrappedGradients;
    private final Set<String> filesWithWrappedGradients = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Svg2Vd <svgDir> <outDir>");
            System.exit(2);
        }
        System.exit(new Svg2Vd().run(Paths.get(args[0]), Paths.get(args[1])));
    }

    private int run(Path svgDir, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        List<Path> svgs;
        try (Stream<Path> s = Files.list(svgDir)) {
            svgs = s.filter(p -> p.getFileName().toString().endsWith(".svg")).sorted().collect(Collectors.toList());
        }
        Set<String> written = new TreeSet<>();
        int line = 0;
        int art = 0;
        for (Path svg : svgs) {
            String base = svg.getFileName().toString().replaceFirst("\\.svg$", "");
            boolean isLine = base.endsWith("_line");
            boolean isArt = base.endsWith("_3d");
            if (!base.matches("[a-z][a-z0-9_]*") || !(isLine || isArt)) {
                problems.add(base + ".svg: name must be <name>_line.svg or <name>_3d.svg in lower case with underscores");
                continue;
            }
            try {
                String xml = convert(svg, base, isLine);
                String outName = "ic_" + base + ".xml";
                Files.write(outDir.resolve(outName), xml.getBytes(StandardCharsets.UTF_8));
                written.add(outName);
                if (isLine) line++; else art++;
            } catch (Exception e) {
                problems.add(base + ".svg: " + e);
            }
        }
        // Drawables left over from an icon that no longer has an SVG.
        try (Stream<Path> s = Files.list(outDir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("ic_.+_(line|3d)\\.xml") && !written.contains(n))
                    .sorted()
                    .forEach(n -> problems.add(n + ": no matching SVG in " + svgDir + " (stale drawable?)"));
        }
        System.out.println("Converted " + (line + art) + " SVG files (" + line + " line, " + art + " 3D) into " + outDir);
        System.out.println("Elliptical radial gradients kept with a <group>: " + wrappedGradients
                + " in " + filesWithWrappedGradients.size() + " files " + filesWithWrappedGradients);
        if (problems.isEmpty()) {
            System.out.println("No converter messages, all checks passed.");
            return 0;
        }
        System.out.println(problems.size() + " PROBLEM(S):");
        problems.forEach(p -> System.out.println("  " + p));
        return 1;
    }

    private String convert(Path svg, String base, boolean isLine) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String messages = Svg2Vector.parseSvgToXml(svg, buf);
        if (messages != null && !messages.trim().isEmpty()) {
            problems.add(base + ".svg: converter reported: " + messages.trim().replace("\n", " | "));
        }
        String xml = buf.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!xml.startsWith("<vector")) throw new IllegalStateException("converter produced no <vector>");

        Document svgDoc = parseXml(Files.readAllBytes(svg));
        List<Element> svgPaths = elements(svgDoc, "path");

        if (isLine) {
            xml = whiten(xml, base);
        }
        xml = keepEllipticalGradients(xml, svgDoc, svgPaths, base);
        xml = "<!-- Generated from design/icons/svg/" + base + ".svg by design/tools/svg2vd. Do not edit: change the SVG and re-run. -->\n" + xml;

        check(xml, base, isLine, svgPaths);
        return xml;
    }

    /** Line icons: every visible colour becomes opaque white. Reports an icon that is not single-colour. */
    private String whiten(String xml, String base) {
        Set<String> colours = new TreeSet<>();
        Matcher m = COLOR_ATTR.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = m.group(2);
            if (value.equals(TRANSPARENT)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                colours.add(value.toLowerCase());
                m.appendReplacement(sb, "android:" + m.group(1) + "=\"" + WHITE + "\"");
            }
        }
        m.appendTail(sb);
        if (colours.size() > 1) {
            problems.add(base + ".svg: line icon uses more than one colour " + colours + ", all were written as " + WHITE);
        }
        return sb.toString();
    }

    /**
     * Wraps each path filled with an elliptical radial gradient in a group that squashes it around the
     * gradient centre, so the circular VectorDrawable gradient is drawn as the ellipse of the design.
     */
    private String keepEllipticalGradients(String xml, Document svgDoc, List<Element> svgPaths, String base) {
        Map<String, Element> gradients = new HashMap<>();
        for (String tag : new String[] {"linearGradient", "radialGradient"}) {
            for (Element g : elements(svgDoc, tag)) gradients.put(g.getAttribute("id"), g);
        }
        Map<Integer, double[]> ellipses = new HashMap<>(); // path index -> centreX, centreY, rotation, scaleY, radius
        for (int i = 0; i < svgPaths.size(); i++) {
            Matcher ref = URL_REF.matcher(svgPaths.get(i).getAttribute("fill"));
            if (!ref.find()) continue;
            Element g = gradients.get(ref.group(1));
            if (g == null) {
                problems.add(base + ".svg: path " + (i + 1) + " refers to a missing gradient " + ref.group(1));
                continue;
            }
            String transform = g.getAttribute("gradientTransform").trim();
            if (transform.isEmpty()) continue;
            if (!g.getTagName().equals("radialGradient")) {
                problems.add(base + ".svg: gradientTransform on a linear gradient is not checked by this tool: " + transform);
                continue;
            }
            Matcher t = GRADIENT_TRANSFORM.matcher(transform);
            if (!t.matches()) {
                problems.add(base + ".svg: unsupported gradientTransform \"" + transform + "\"");
                continue;
            }
            double tx = Double.parseDouble(t.group(1));
            double ty = Double.parseDouble(t.group(2));
            double rot = t.group(3) == null ? 0 : Double.parseDouble(t.group(3));
            double sx = Double.parseDouble(t.group(4));
            double sy = t.group(5) == null ? sx : Double.parseDouble(t.group(5));
            if (Math.abs(sx - sy) < 1e-9) continue; // a circle: the converter output is already exact
            if (!"userSpaceOnUse".equals(g.getAttribute("gradientUnits"))) {
                problems.add(base + ".svg: elliptical gradient " + g.getAttribute("id") + " is not in userSpaceOnUse units");
                continue;
            }
            double cx;
            double cy;
            double r;
            try {
                cx = Double.parseDouble(g.getAttribute("cx"));
                cy = Double.parseDouble(g.getAttribute("cy"));
                r = Double.parseDouble(g.getAttribute("r"));
            } catch (NumberFormatException e) {
                problems.add(base + ".svg: elliptical gradient " + g.getAttribute("id") + " needs numeric cx, cy and r");
                continue;
            }
            double cos = Math.cos(Math.toRadians(rot));
            double sin = Math.sin(Math.toRadians(rot));
            double centreX = tx + sx * cx * cos - sy * cy * sin;
            double centreY = ty + sx * cx * sin + sy * cy * cos;
            ellipses.put(i, new double[] {centreX, centreY, rot, sy / sx, r * sx});
        }
        if (ellipses.isEmpty()) return xml;

        // Path blocks of the converter output, in document order (same order as the SVG paths).
        List<String> lines = new ArrayList<>(List.of(xml.split("\n", -1)));
        List<int[]> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).equals("  <path")) continue;
            int j = i + 1;
            boolean open = false;
            for (; j < lines.size(); j++) {
                String l = lines.get(j);
                if (!open) {
                    if (l.endsWith("/>")) break;
                    if (l.endsWith(">")) open = true;
                } else if (l.equals("  </path>")) {
                    break;
                }
            }
            blocks.add(new int[] {i, j});
            i = j;
        }
        if (blocks.size() != svgPaths.size()) {
            problems.add(base + ".svg: cannot keep elliptical gradients, the converter wrote " + blocks.size()
                    + " top-level paths for " + svgPaths.size() + " SVG paths");
            return xml;
        }

        List<String> out = new ArrayList<>();
        int next = 0;
        for (int b = 0; b < blocks.size(); b++) {
            int[] block = blocks.get(b);
            for (; next < block[0]; next++) out.add(lines.get(next));
            double[] e = ellipses.get(b);
            if (e == null) {
                for (; next <= block[1]; next++) out.add(lines.get(next));
                continue;
            }
            String d = transformPath(svgPaths.get(b).getAttribute("d"), e[0], e[1], e[2], e[3]);
            out.add("  <!-- Elliptical radial gradient: drawn as a circle inside a squashed group, path data pre-transformed to match. -->");
            out.add("  <group");
            out.add("      android:pivotX=\"" + fmt(e[0], 4) + "\"");
            out.add("      android:pivotY=\"" + fmt(e[1], 4) + "\"");
            if (e[2] != 0) out.add("      android:rotation=\"" + fmt(e[2], 4) + "\"");
            out.add("      android:scaleY=\"" + fmt(e[3], 6) + "\">");
            boolean replaced = false;
            for (; next <= block[1]; next++) {
                String l = lines.get(next);
                Matcher pd = PATH_DATA_ATTR.matcher(l);
                if (pd.find()) {
                    l = pd.replaceFirst(Matcher.quoteReplacement("android:pathData=\"" + d + "\""));
                    replaced = true;
                }
                expectGradientAttr(l, "centerX", e[0], base);
                expectGradientAttr(l, "centerY", e[1], base);
                expectGradientAttr(l, "gradientRadius", e[4], base);
                out.add("  " + l);
            }
            out.add("  </group>");
            if (!replaced) problems.add(base + ".svg: path " + (b + 1) + " has no pathData in the converter output");
            wrappedGradients++;
            filesWithWrappedGradients.add("ic_" + base + ".xml");
        }
        for (; next < lines.size(); next++) out.add(lines.get(next));
        return String.join("\n", out);
    }

    /** The group trick relies on the centre and radius the converter wrote: make sure they are what we expect. */
    private void expectGradientAttr(String line, String attr, double expected, String base) {
        Matcher m = Pattern.compile("android:" + attr + "=\"([^\"]*)\"").matcher(line);
        if (m.find() && Math.abs(Double.parseDouble(m.group(1)) - expected) > 0.0015) {
            problems.add(base + ".svg: gradient " + attr + " is " + m.group(1) + ", expected " + fmt(expected, 4));
        }
    }

    /**
     * Applies the inverse of the group transform (pivot = centre, rotation, scaleY = k) to SVG path data.
     * All commands become absolute M, L, C, Q and Z.
     */
    static String transformPath(String d, double centreX, double centreY, double rotation, double k) {
        double cos = Math.cos(Math.toRadians(rotation));
        double sin = Math.sin(Math.toRadians(rotation));
        List<String> tokens = new ArrayList<>();
        Matcher m = PATH_TOKEN.matcher(d);
        while (m.find()) tokens.add(m.group());
        StringBuilder out = new StringBuilder();
        double x = 0, y = 0, startX = 0, startY = 0, ctrlX = 0, ctrlY = 0;
        char cmd = 0;
        char last = 0;
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (Character.isLetter(t.charAt(0))) {
                cmd = t.charAt(0);
                i++;
            } else if (cmd == 'M') {
                cmd = 'L';
            } else if (cmd == 'm') {
                cmd = 'l';
            } else if (cmd == 0 || cmd == 'Z' || cmd == 'z') {
                throw new IllegalArgumentException("bad path data: " + d);
            }
            boolean rel = Character.isLowerCase(cmd);
            char c = Character.toUpperCase(cmd);
            int n = "MLT".indexOf(c) >= 0 ? 2 : "HV".indexOf(c) >= 0 ? 1 : c == 'C' ? 6 : "SQ".indexOf(c) >= 0 ? 4 : c == 'Z' ? 0 : -1;
            if (n < 0) throw new IllegalArgumentException("path command " + cmd + " is not supported: " + d);
            if (i + n > tokens.size()) throw new IllegalArgumentException("bad path data: " + d);
            double[] a = new double[n];
            for (int j = 0; j < n; j++) a[j] = Double.parseDouble(tokens.get(i++));
            double ox = rel ? x : 0;
            double oy = rel ? y : 0;
            switch (c) {
                case 'M':
                    x = a[0] + ox; y = a[1] + oy; startX = x; startY = y;
                    out.append('M').append(point(x, y, centreX, centreY, cos, sin, k));
                    break;
                case 'L':
                    x = a[0] + ox; y = a[1] + oy;
                    out.append('L').append(point(x, y, centreX, centreY, cos, sin, k));
                    break;
                case 'H':
                    x = a[0] + ox;
                    out.append('L').append(point(x, y, centreX, centreY, cos, sin, k));
                    break;
                case 'V':
                    y = a[0] + oy;
                    out.append('L').append(point(x, y, centreX, centreY, cos, sin, k));
                    break;
                case 'C':
                case 'S': {
                    double x1, y1, x2, y2;
                    int o = 0;
                    if (c == 'C') {
                        x1 = a[0] + ox; y1 = a[1] + oy; o = 2;
                    } else {
                        boolean smooth = last == 'C' || last == 'S';
                        x1 = smooth ? 2 * x - ctrlX : x;
                        y1 = smooth ? 2 * y - ctrlY : y;
                    }
                    x2 = a[o] + ox; y2 = a[o + 1] + oy;
                    x = a[o + 2] + ox; y = a[o + 3] + oy;
                    ctrlX = x2; ctrlY = y2;
                    out.append('C').append(point(x1, y1, centreX, centreY, cos, sin, k))
                            .append(' ').append(point(x2, y2, centreX, centreY, cos, sin, k))
                            .append(' ').append(point(x, y, centreX, centreY, cos, sin, k));
                    break;
                }
                case 'Q':
                case 'T': {
                    double x1, y1;
                    int o = 0;
                    if (c == 'Q') {
                        x1 = a[0] + ox; y1 = a[1] + oy; o = 2;
                    } else {
                        boolean smooth = last == 'Q' || last == 'T';
                        x1 = smooth ? 2 * x - ctrlX : x;
                        y1 = smooth ? 2 * y - ctrlY : y;
                    }
                    x = a[o] + ox; y = a[o + 1] + oy;
                    ctrlX = x1; ctrlY = y1;
                    out.append('Q').append(point(x1, y1, centreX, centreY, cos, sin, k))
                            .append(' ').append(point(x, y, centreX, centreY, cos, sin, k));
                    break;
                }
                default: // Z
                    x = startX; y = startY;
                    out.append('Z');
            }
            last = c;
        }
        return out.toString();
    }

    /** Inverse of: translate(centre) rotate(rotation) scale(1, k) translate(-centre). */
    private static String point(double x, double y, double centreX, double centreY, double cos, double sin, double k) {
        double dx = x - centreX;
        double dy = y - centreY;
        double rx = dx * cos + dy * sin;
        double ry = -dx * sin + dy * cos;
        return fmt(centreX + rx, 4) + "," + fmt(centreY + ry / k, 4);
    }

    private static String fmt(double v, int decimals) {
        BigDecimal b = new BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        return b.signum() == 0 ? "0" : b.toPlainString();
    }

    private void check(String xml, String base, boolean isLine, List<Element> svgPaths) {
        Document doc;
        try {
            doc = parseXml(xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            problems.add("ic_" + base + ".xml: not well-formed XML: " + e.getMessage());
            return;
        }
        Element root = doc.getDocumentElement();
        String size = isLine ? "24" : "32";
        String[][] expected = {
            {"android:width", size + "dp"}, {"android:height", size + "dp"},
            {"android:viewportWidth", size}, {"android:viewportHeight", size}
        };
        if (!root.getTagName().equals("vector")) problems.add("ic_" + base + ".xml: root is not <vector>");
        for (String[] e : expected) {
            if (!e[1].equals(root.getAttribute(e[0]))) {
                problems.add("ic_" + base + ".xml: " + e[0] + " is \"" + root.getAttribute(e[0]) + "\", expected \"" + e[1] + "\"");
            }
        }
        List<Element> paths = elements(doc, "path");
        if (paths.size() != svgPaths.size()) {
            problems.add("ic_" + base + ".xml: " + paths.size() + " paths, the SVG has " + svgPaths.size());
        }
        long gradients = elements(doc, "gradient").size();
        long alphas = paths.stream().filter(p -> p.hasAttribute("android:fillAlpha") || p.hasAttribute("android:strokeAlpha")).count();
        long svgGradientFills = svgPaths.stream().filter(p -> p.getAttribute("fill").startsWith("url(") || p.getAttribute("stroke").startsWith("url(")).count();
        long svgOpacities = svgPaths.stream().filter(p -> p.hasAttribute("opacity") || p.hasAttribute("fill-opacity") || p.hasAttribute("stroke-opacity")).count();
        if (gradients != svgGradientFills) {
            problems.add("ic_" + base + ".xml: " + gradients + " gradients, the SVG has " + svgGradientFills + " gradient-filled paths");
        }
        if (alphas != svgOpacities) {
            problems.add("ic_" + base + ".xml: " + alphas + " paths with alpha, the SVG has " + svgOpacities + " paths with opacity");
        }
        if (isLine) {
            if (gradients != 0) problems.add("ic_" + base + ".xml: a line icon must not have gradients");
            for (Element p : paths) {
                for (String attr : new String[] {"android:strokeColor", "android:fillColor"}) {
                    String v = p.getAttribute(attr);
                    if (!v.isEmpty() && !v.equals(WHITE) && !v.equals(TRANSPARENT)) {
                        problems.add("ic_" + base + ".xml: " + attr + " is " + v + ", expected " + WHITE);
                    }
                }
            }
        }
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static List<Element> elements(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }
}
