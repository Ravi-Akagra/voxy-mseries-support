package me.cortex.voxy.client.core.gpu;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the EFFECTIVE {@code layout(local_size_x=..., local_size_y=..., local_size_z=...)}
 * declaration out of preprocessed GLSL compute source ({@code ShaderLoader.parse}
 * has already resolved {@code #import}s by the time a {@link ComputePipelineDesc}
 * is built).
 *
 * Motivation: Metal needs {@code threadsPerThreadgroup} at dispatch time, and the
 * desc's hand-written localSize fields drifted from the shaders (cmdgen/prefixSum/
 * translucentGen said 32 vs the shaders' 128/256), silently dropping ~75% of
 * threads per dispatch — the 2026-05 LOD flicker. GL is immune because
 * {@code glDispatchCompute} always uses the shader-declared size; this parser makes
 * the shader source authoritative on Metal too.
 *
 * Size tokens are resolved first against the desc's defines map, then against
 * in-source {@code #define TOKEN value} lines; values may be small constant integer
 * expressions (e.g. {@code (1<<LOCAL_SIZE_BITS)} in traversal_dev.comp). Missing
 * local_size_y/z default to 1. Anything unresolvable THROWS at pipeline creation —
 * never dispatch with a guessed size.
 */
public final class ComputeLocalSizeParser {

    private ComputeLocalSizeParser() {}

    /** Parsed local size; y/z default to 1 when undeclared. */
    public record LocalSize(int x, int y, int z) {}

    // layout(...) in;  containing local_size_x. [^)]* is safe: GLSL layout
    // qualifier lists never nest parentheses (size expressions live behind
    // macro tokens, expanded during resolution, not inline in the layout).
    private static final Pattern LAYOUT_PATTERN = Pattern.compile(
            "layout\\s*\\(([^)]*\\blocal_size_x\\b[^)]*)\\)\\s*in\\s*;");

    private static final int MAX_MACRO_DEPTH = 32;

    public static LocalSize parse(String glsl, Map<String, String> defines, String label) {
        if (glsl == null) {
            throw new IllegalArgumentException("ComputeLocalSizeParser[" + label + "]: null GLSL source");
        }
        Matcher m = LAYOUT_PATTERN.matcher(glsl);
        if (!m.find()) {
            throw new IllegalStateException("ComputeLocalSizeParser[" + label
                    + "]: no layout(local_size_x=...) in; declaration found in compute source");
        }
        int x = -1, y = 1, z = 1;
        for (String part : m.group(1).split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim();
            String expr = part.substring(eq + 1).trim();
            switch (key) {
                case "local_size_x" -> x = evaluate(expr, defines, glsl, label, 0);
                case "local_size_y" -> y = evaluate(expr, defines, glsl, label, 0);
                case "local_size_z" -> z = evaluate(expr, defines, glsl, label, 0);
                default -> { /* other layout qualifiers (e.g. std430) — ignore */ }
            }
        }
        if (x < 1 || y < 1 || z < 1) {
            throw new IllegalStateException("ComputeLocalSizeParser[" + label
                    + "]: invalid local size (" + x + "," + y + "," + z + ")");
        }
        return new LocalSize(x, y, z);
    }

    private static int evaluate(String expr, Map<String, String> defines, String glsl, String label, int depth) {
        if (depth > MAX_MACRO_DEPTH) {
            throw new IllegalStateException("ComputeLocalSizeParser[" + label
                    + "]: macro expansion too deep evaluating '" + expr + "' (cycle?)");
        }
        return new ExprParser(expr, defines, glsl, label, depth).parseFull();
    }

    /**
     * Minimal constant-int expression evaluator: literals (decimal/hex),
     * identifiers (macro-resolved), parentheses, unary +/-, and the binary
     * operators {@code * / % + - << >>} with C precedence. Enough for every
     * local-size expression in the tree; anything else fails loud.
     */
    private static final class ExprParser {
        private final String src;
        private final Map<String, String> defines;
        private final String glsl;
        private final String label;
        private final int depth;
        private int pos;

        ExprParser(String src, Map<String, String> defines, String glsl, String label, int depth) {
            this.src = src;
            this.defines = defines;
            this.glsl = glsl;
            this.label = label;
            this.depth = depth;
        }

        int parseFull() {
            int v = parseShift();
            skipWs();
            if (this.pos != this.src.length()) {
                throw fail("trailing characters at index " + this.pos);
            }
            return v;
        }

        private int parseShift() {
            int v = parseAdditive();
            while (true) {
                skipWs();
                if (peek2("<<")) { this.pos += 2; v = v << parseAdditive(); }
                else if (peek2(">>")) { this.pos += 2; v = v >> parseAdditive(); }
                else return v;
            }
        }

        private int parseAdditive() {
            int v = parseMultiplicative();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '+') { this.pos++; v = v + parseMultiplicative(); }
                else if (c == '-') { this.pos++; v = v - parseMultiplicative(); }
                else return v;
            }
        }

        private int parseMultiplicative() {
            int v = parseUnary();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '*') { this.pos++; v = v * parseUnary(); }
                else if (c == '/') { this.pos++; v = v / parseUnary(); }
                else if (c == '%') { this.pos++; v = v % parseUnary(); }
                else return v;
            }
        }

        private int parseUnary() {
            skipWs();
            char c = peek();
            if (c == '-') { this.pos++; return -parseUnary(); }
            if (c == '+') { this.pos++; return parseUnary(); }
            return parsePrimary();
        }

        private int parsePrimary() {
            skipWs();
            char c = peek();
            if (c == '(') {
                this.pos++;
                int v = parseShift();
                skipWs();
                if (peek() != ')') throw fail("expected ')' at index " + this.pos);
                this.pos++;
                return v;
            }
            if (Character.isDigit(c)) {
                int start = this.pos;
                while (this.pos < this.src.length()
                        && (Character.isLetterOrDigit(this.src.charAt(this.pos)))) {
                    this.pos++;
                }
                String num = this.src.substring(start, this.pos);
                // strip GLSL unsigned suffix
                if (num.endsWith("u") || num.endsWith("U")) num = num.substring(0, num.length() - 1);
                try {
                    return num.startsWith("0x") || num.startsWith("0X")
                            ? Integer.parseInt(num.substring(2), 16)
                            : Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    throw fail("bad integer literal '" + num + "'");
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = this.pos;
                while (this.pos < this.src.length()
                        && (Character.isLetterOrDigit(this.src.charAt(this.pos)) || this.src.charAt(this.pos) == '_')) {
                    this.pos++;
                }
                return resolveToken(this.src.substring(start, this.pos));
            }
            throw fail("unexpected character '" + c + "' at index " + this.pos);
        }

        private int resolveToken(String token) {
            // Desc defines win over in-source #defines (matches the GLSL
            // preprocessor: injected defines precede the source).
            String value = this.defines == null ? null : this.defines.get(token);
            if (value == null || value.isBlank()) {
                value = findInSourceDefine(token);
            }
            if (value == null || value.isBlank()) {
                throw fail("cannot resolve token '" + token + "' to an integer (not in desc defines or #define lines)");
            }
            return evaluate(value.trim(), this.defines, this.glsl, this.label, this.depth + 1);
        }

        private String findInSourceDefine(String token) {
            // Anchored at line start so commented-out '//#define' lines don't match.
            Matcher m = Pattern.compile(
                    "(?m)^[ \\t]*#[ \\t]*define[ \\t]+" + Pattern.quote(token) + "(?![\\w])[ \\t]+(.+)$")
                    .matcher(this.glsl);
            if (!m.find()) return null;
            String value = m.group(1);
            int comment = value.indexOf("//");
            if (comment >= 0) value = value.substring(0, comment);
            return value;
        }

        private char peek() {
            return this.pos < this.src.length() ? this.src.charAt(this.pos) : '\0';
        }

        private boolean peek2(String op) {
            return this.src.startsWith(op, this.pos);
        }

        private void skipWs() {
            while (this.pos < this.src.length() && Character.isWhitespace(this.src.charAt(this.pos))) {
                this.pos++;
            }
        }

        private IllegalStateException fail(String msg) {
            return new IllegalStateException("ComputeLocalSizeParser[" + this.label
                    + "]: " + msg + " in expression '" + this.src + "'");
        }
    }
}
