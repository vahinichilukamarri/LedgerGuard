package com.ledgerguard.detection.explain.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numbers, as a narrative states them and as the evidence holds them.
 *
 * <h2>The problem this solves</h2>
 *
 * The strongest check available on a generated narrative is that every number
 * in it came from the record. Exact string matching cannot do that job: the
 * evidence holds {@code 0.556} and a good sentence says "0.56", or "56% of the
 * composite", or "0.6 of the composite" — all three are truthful renderings and
 * only the first survives a string comparison.
 *
 * <p>So a stated number is grounded when some evidence value, <em>rounded to the
 * precision the narrative chose to write</em>, equals it. "0.56" grounds against
 * 0.556 because 0.556 to two decimals is 0.56; "0.58" does not ground against
 * anything, which is the case that matters.
 *
 * <h2>The percent allowance, and what it costs</h2>
 *
 * A value in {@code [0,1]} also grounds its hundredfold, so 0.70 licenses "70%"
 * and 0.99 licenses "the 99th percentile". This is a real loosening: a bare
 * "70" in a sentence where 70 means something other than a percentage would
 * pass. It is accepted because percentages are how these quantities read
 * naturally, and because the alternative — parsing the {@code %} sign and the
 * word "percentile" out of free prose — would fail in the direction of
 * rejecting correct narratives, which costs a fallback on every good output
 * rather than catching a rare bad one.
 */
public final class NumericGrounding {

    /**
     * Numbers as prose writes them: optional sign, optional thousands
     * separators, optional decimal part. Scientific notation is matched too,
     * because Phase 8's own signal sentences contain p-values like
     * {@code 2.41e-14} and a narrative quoting one is quoting the evidence.
     */
    private static final Pattern NUMBER = Pattern.compile(
            "-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

    /**
     * Floating-point slack, <b>relative</b> rather than absolute.
     *
     * <p>An absolute epsilon looked fine until a test asked whether 2.41e-14
     * grounds against 2.41e-13. They differ by 2.17e-13, which is under any
     * absolute tolerance worth having, so the two were equal — and Phase 8's
     * signal sentences quote p-values at exactly that magnitude. An absolute
     * epsilon says every sufficiently small number is every other one.
     */
    private static final double RELATIVE_EPSILON = 1e-9;

    private NumericGrounding() {
    }

    /** Every number appearing in {@code text}, in order, as written. */
    public static List<Stated> statedIn(String text) {
        List<Stated> stated = new ArrayList<>();
        if (text == null) {
            return stated;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            String cleaned = token.replace(",", "");
            try {
                stated.add(new Stated(token, Double.parseDouble(cleaned), decimalsOf(cleaned)));
            } catch (NumberFormatException ignored) {
                // Not a number this system needs to ground; the regex is
                // deliberately broader than the parser.
            }
        }
        return stated;
    }

    /** Every number appearing in {@code text}, as plain values. */
    public static List<Double> valuesIn(String text) {
        return statedIn(text).stream().map(Stated::value).toList();
    }

    /**
     * Whether {@code stated} is a truthful rendering of any evidence value.
     */
    public static boolean isGrounded(Stated stated, Collection<Double> evidence) {
        for (double value : evidence) {
            if (matches(stated, value) || matches(stated, value * 100.0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Stated stated, double evidence) {
        if (nearlyEqual(stated.value(), evidence)) {
            return true;
        }
        if (!Double.isFinite(evidence)) {
            return false;
        }
        double rounded = BigDecimal.valueOf(evidence)
                .setScale(stated.decimals(), RoundingMode.HALF_UP)
                .doubleValue();
        return nearlyEqual(stated.value(), rounded);
    }

    /** Equal to within a relative tolerance, so magnitude does not decide the answer. */
    private static boolean nearlyEqual(double left, double right) {
        if (left == right) {
            return true;
        }
        double magnitude = Math.max(Math.abs(left), Math.abs(right));
        return Math.abs(left - right) <= RELATIVE_EPSILON * magnitude;
    }

    private static int decimalsOf(String token) {
        int exponent = Math.max(token.indexOf('e'), token.indexOf('E'));
        String mantissa = exponent < 0 ? token : token.substring(0, exponent);
        int point = mantissa.indexOf('.');
        return point < 0 ? 0 : mantissa.length() - point - 1;
    }

    /**
     * One number as the narrative wrote it.
     *
     * @param decimals how many decimal places the narrative chose, which is what
     *                 the evidence gets rounded to before comparison
     */
    public record Stated(String token, double value, int decimals) {
    }
}
