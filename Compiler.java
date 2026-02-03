import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Compiler {
    private static final Set<String> STRING_FIELDS = new HashSet<>(
            Arrays.asList("customerId", "country", "state", "region", "city", "postalCode", "priceType")
    );
    private static final Set<String> NUMERIC_FIELDS = new HashSet<>(
            Arrays.asList("value", "quantity", "discountValue", "itemValue")
    );
    private static final Set<String> ALL_FIELDS = new HashSet<>();

    private static final Map<String, String> OPERATOR_MAP = new HashMap<>();
    private static final Map<String, String> LOGICAL_MAP = new HashMap<>();

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");
    private static final String BETWEEN_PLACEHOLDER = "__BETWEEN_AND__";
    private static final Pattern BETWEEN_PATTERN = Pattern.compile(
            "\\b(\\w+)\\s+between\\s+([^\\s]+)\\s+and\\s+([^\\s]+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OP_PATTERN = Pattern.compile(
            "^(?<field>\\w+)\\s*(?<operator>==|!=|>=|<=|>|<|=|equals|is|more than|greater than|less than)\\s*(?<value>.+)$",
            Pattern.CASE_INSENSITIVE
    );

    static {
        ALL_FIELDS.addAll(STRING_FIELDS);
        ALL_FIELDS.addAll(NUMERIC_FIELDS);

        OPERATOR_MAP.put("=", "==");
        OPERATOR_MAP.put("equals", "==");
        OPERATOR_MAP.put("is", "==");
        OPERATOR_MAP.put("more than", ">");
        OPERATOR_MAP.put("greater than", ">");
        OPERATOR_MAP.put("less than", "<");

        LOGICAL_MAP.put("and", "&&");
        LOGICAL_MAP.put("also", "&&");
        LOGICAL_MAP.put("or", "||");
    }

    private Compiler() {
    }

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder input = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            input.append(line).append('\n');
        }
        String result = compileCondition(input.toString());
        System.out.print(result);
    }

    private static String compileCondition(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "INVALID_CONDITION";
        }

        TokenizedConditions tokens = tokenizeConditions(text);
        if (tokens == null) {
            return "INVALID_CONDITION";
        }

        List<String> parsedConditions = new ArrayList<>();
        for (String condition : tokens.conditions) {
            String parsed = parseCondition(condition);
            if (parsed == null) {
                return "INVALID_CONDITION";
            }
            parsedConditions.add(parsed);
        }

        String expression = parsedConditions.get(0);
        for (int i = 0; i < tokens.connectors.size(); i++) {
            String connector = tokens.connectors.get(i);
            String logical = LOGICAL_MAP.get(connector);
            if (logical == null) {
                return "INVALID_CONDITION";
            }
            expression = "(" + expression + " " + logical + " " + parsedConditions.get(i + 1) + ")";
        }

        return expression;
    }

    private static String parseCondition(String condition) {
        if (condition == null) {
            return null;
        }
        String trimmed = condition.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String betweenParsed = parseBetween(trimmed);
        if (betweenParsed != null) {
            return betweenParsed;
        }

        Matcher matcher = OP_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }

        String field = canonicalField(matcher.group("field"));
        if (field == null) {
            return null;
        }

        String operatorRaw = matcher.group("operator").toLowerCase(Locale.ROOT);
        String operator = OPERATOR_MAP.getOrDefault(operatorRaw, operatorRaw);
        String value = normalizeValue(matcher.group("value"), field);
        if (value == null) {
            return null;
        }

        return "(" + field + " " + operator + " " + value + ")";
    }

    private static String parseBetween(String condition) {
        Matcher matcher = Pattern.compile(
                "^(?<field>\\w+)\\s+between\\s+(?<low>[^\\s]+)\\s+"
                        + Pattern.quote(BETWEEN_PLACEHOLDER)
                        + "\\s+(?<high>[^\\s]+)$",
                Pattern.CASE_INSENSITIVE
        ).matcher(condition);

        if (!matcher.matches()) {
            return null;
        }

        String field = canonicalField(matcher.group("field"));
        if (field == null) {
            return null;
        }

        String low = normalizeValue(matcher.group("low"), field);
        String high = normalizeValue(matcher.group("high"), field);
        if (low == null || high == null) {
            return null;
        }

        return "(" + field + " >= " + low + " && " + field + " <= " + high + ")";
    }

    private static TokenizedConditions tokenizeConditions(String text) {
        String protectedText = protectBetween(text);
        String[] parts = protectedText.split("(?i)\\b(and|also|or)\\b");
        List<String> connectors = new ArrayList<>();

        Matcher matcher = Pattern.compile("(?i)\\b(and|also|or)\\b").matcher(protectedText);
        while (matcher.find()) {
            connectors.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }

        if (parts.length == 0 || parts.length - 1 != connectors.size()) {
            return null;
        }

        List<String> conditions = new ArrayList<>();
        for (String part : parts) {
            conditions.add(part.trim());
        }

        if (conditions.isEmpty()) {
            return null;
        }

        return new TokenizedConditions(conditions, connectors);
    }

    private static String protectBetween(String text) {
        Matcher matcher = BETWEEN_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + " between " + matcher.group(2) + " "
                    + BETWEEN_PLACEHOLDER + " " + matcher.group(3);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String normalizeValue(String rawValue, String field) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return null;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("one time") || lower.equals("one-time") || lower.equals("onetime")) {
            value = "Onetime";
        } else if (lower.equals("recurring")) {
            value = "Recurring";
        }

        if (NUMERIC_FIELDS.contains(field)) {
            if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
                return null;
            }
            if (!NUMERIC_PATTERN.matcher(value).matches()) {
                return null;
            }
            return value;
        }

        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
        }
        return "'" + value + "'";
    }

    private static String canonicalField(String field) {
        if (field == null) {
            return null;
        }
        for (String allowed : ALL_FIELDS) {
            if (allowed.equalsIgnoreCase(field)) {
                return allowed;
            }
        }
        return null;
    }

    private static final class TokenizedConditions {
        private final List<String> conditions;
        private final List<String> connectors;

        private TokenizedConditions(List<String> conditions, List<String> connectors) {
            this.conditions = conditions;
            this.connectors = connectors;
        }
    }
}
